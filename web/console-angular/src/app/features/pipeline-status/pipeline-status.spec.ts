import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ControllableGateway, gatewayError } from '../../../testing/controllable-gateway';
import { ENVIRONMENT, type AppEnvironment } from '../../core/environment';
import { PatientGateway } from '../../data/patient-gateway';
import type { PipelineStatus, ServiceStatus } from '../../domain/pipeline';
import { PipelineStatusPage } from './pipeline-status';

const POLL_MS = 10_000;

const TEST_ENVIRONMENT: AppEnvironment = {
  label: 'spec',
  gateway: 'in-memory',
  gatewayBaseUrl: '',
  pipelinePollMs: POLL_MS,
};

function service(overrides: Partial<ServiceStatus> = {}): ServiceStatus {
  return {
    id: 'hl7-ingest',
    displayName: 'HL7 ingest',
    runtime: 'Java 21',
    stage: 'ingest',
    health: 'healthy',
    detail: 'MLLP listener accepting on :2575',
    checkedAt: '2026-08-25T14:30:00.000Z',
    counters: { accepted: 10, rejected: 0, failed: 0 },
    ...overrides,
  };
}

function snapshot(services: ServiceStatus[]): PipelineStatus {
  return { services, observedAt: '2026-08-25T14:30:00.000Z' };
}

describe('PipelineStatusPage', () => {
  let fixture: ComponentFixture<PipelineStatusPage>;
  let gateway: ControllableGateway;

  beforeEach(() => {
    vi.useFakeTimers({
      toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'],
    });
    gateway = new ControllableGateway();

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: PatientGateway, useValue: gateway },
        { provide: ENVIRONMENT, useValue: TEST_ENVIRONMENT },
      ],
    });

    fixture = TestBed.createComponent(PipelineStatusPage);
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** Drains the resource's promise boundary and renders. */
  async function render(): Promise<HTMLElement> {
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('polls on the interval the environment configures', async () => {
    await render();
    expect(gateway.pipelineCalls).toHaveLength(1);
    gateway.pipelineCalls[0]?.resolve(snapshot([service()]));

    await vi.advanceTimersByTimeAsync(POLL_MS);
    expect(gateway.pipelineCalls).toHaveLength(2);
  });

  it('stops polling once the component is destroyed', async () => {
    await render();
    gateway.pipelineCalls[0]?.resolve(snapshot([service()]));

    fixture.destroy();
    await vi.advanceTimersByTimeAsync(POLL_MS * 3);

    // An interval left running outside the reactive graph keeps issuing a request every
    // poll period for the rest of the session, against a component nobody is looking at.
    expect(gateway.pipelineCalls).toHaveLength(1);
  });

  it('does not stack polls when one is still in flight', async () => {
    await render();
    // Deliberately never answered — a degraded backend is exactly when this page is open.
    await vi.advanceTimersByTimeAsync(POLL_MS * 3);

    // `switchMap` drops the superseded poll; `mergeMap` semantics would pile four requests
    // onto a service that is already struggling.
    expect(gateway.pipelineCalls.filter((call) => !call.cancelled)).toHaveLength(1);
  });

  it('renders one card per service, in pipeline flow order', async () => {
    await render();
    gateway.pipelineCalls[0]?.resolve(
      snapshot([
        service({ id: 'claims-edi', displayName: 'Claims EDI', stage: 'claims' }),
        service({ id: 'patient-gateway', displayName: 'Patient gateway', stage: 'read' }),
        service({ id: 'hl7-ingest', displayName: 'HL7 ingest', stage: 'ingest' }),
      ]),
    );

    const names = [...(await render()).querySelectorAll('.service__name')].map((el) =>
      el.textContent?.trim(),
    );
    // Read as a pipeline: an operator scanning for where a backlog starts needs the stages
    // laid out in the order a message travels through them.
    expect(names).toEqual(['HL7 ingest', 'Patient gateway', 'Claims EDI']);
  });

  it('rolls the header up to the worst state, not to an average', async () => {
    await render();
    gateway.pipelineCalls[0]?.resolve(
      snapshot([
        service({ id: 'a', health: 'healthy' }),
        service({ id: 'b', health: 'healthy' }),
        service({ id: 'c', health: 'down' }),
      ]),
    );

    // A pipeline with two healthy stages and one down stage is not "67% healthy", it is not
    // delivering admissions.
    expect((await render()).querySelector('.overall')?.textContent).toContain('Down');
  });

  it('labels health as text, not only as colour', async () => {
    await render();
    gateway.pipelineCalls[0]?.resolve(snapshot([service({ health: 'degraded' })]));

    // Around 4% of men cannot reliably separate the red and green used here; a dashboard
    // whose meaning is carried by hue alone is unreadable for them.
    const card = (await render()).querySelector('.service');
    expect(card?.querySelector('.service__health')?.textContent).toContain('Degraded');
    expect(card?.getAttribute('data-health')).toBe('degraded');
  });

  it('shows the three dispositions separately', async () => {
    await render();
    gateway.pipelineCalls[0]?.resolve(
      snapshot([service({ counters: { accepted: 48_213, rejected: 96, failed: 137 } })]),
    );

    const counters = (await render()).querySelector('.counters')?.textContent ?? '';
    // `rejected` is an upstream configuration conversation; `failed` is an incident. A single
    // "errors" number puts a sender's misconfigured ADT trigger on the same alert as a
    // broker outage, and the alert then gets muted.
    expect(counters).toContain('48,213');
    expect(counters).toContain('96');
    expect(counters).toContain('137');
  });

  it('says a service has no counters rather than showing zeroes', async () => {
    await render();
    gateway.pipelineCalls[0]?.resolve(snapshot([service({ counters: null })]));

    const element = await render();
    // A green "0 failed" for a read gateway is indistinguishable from "nothing has failed
    // today", which is a materially different claim.
    expect(element.querySelector('.counters')).toBeNull();
    expect(element.textContent).toContain('does not own a message disposition');
  });

  it('excludes rejected messages from the failure rollup', async () => {
    await render();
    gateway.pipelineCalls[0]?.resolve(
      snapshot([
        service({ id: 'a', counters: { accepted: 1, rejected: 500, failed: 2 } }),
        service({ id: 'b', counters: { accepted: 1, rejected: 0, failed: 3 } }),
      ]),
    );

    const live = (await render()).querySelector('[role="status"]')?.textContent ?? '';
    expect(live).toContain('5 failed messages');
    expect(live).not.toContain('505');
  });

  it('renders a recoverable error that does not claim the pipeline is down', async () => {
    await render();
    gateway.pipelineCalls[0]?.fail(gatewayError('unavailable', 'ops endpoint refused'));

    const alert = (await render()).querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('Pipeline status is unavailable');
    // The distinction an operator has to be able to make at 3am: the health endpoint being
    // unreadable says nothing about whether messages are flowing.
    expect(alert?.textContent).toContain('Messages may still be flowing');
    expect(alert?.querySelector('button')).not.toBeNull();
  });
});
