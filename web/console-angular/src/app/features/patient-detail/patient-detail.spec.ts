import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import {
  ControllableGateway,
  gatewayError,
  sampleEncounter,
  samplePatient,
} from '../../../testing/controllable-gateway';
import { PatientGateway } from '../../data/patient-gateway';
import { PatientDetailPage } from './patient-detail';

describe('PatientDetailPage', () => {
  let fixture: ComponentFixture<PatientDetailPage>;
  let gateway: ControllableGateway;

  beforeEach(() => {
    gateway = new ControllableGateway();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PatientGateway, useValue: gateway },
      ],
    });

    fixture = TestBed.createComponent(PatientDetailPage);
    // The MRN arrives as a signal input, so the test sets an input rather than assembling a
    // fake `ActivatedRoute` snapshot — which is the practical payoff of
    // `withComponentInputBinding()`.
    fixture.componentRef.setInput('mrn', 'MRN-88213');
    fixture.detectChanges();
  });

  /**
   * Drains pending microtasks, runs change detection, and returns the rendered DOM.
   *
   * The `await` is not optional. `rxResource` delivers values across a promise boundary, so
   * a value emitted by the fake gateway reaches the resource's signals a microtask later —
   * a bare `detectChanges()` runs before that and renders the loading state, which is what
   * every assertion here would then be checking.
   *
   * Yielding to a `setTimeout` rather than calling `fixture.whenStable()`: the fake gateway
   * deliberately leaves calls open so a test can decide when (and whether) they answer, and
   * an open call keeps the application unstable — so `whenStable()` never resolves for
   * exactly the states this file needs to assert on, starting with "still loading".
   */
  async function render(): Promise<HTMLElement> {
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function resolvePatient(overrides: Parameters<typeof samplePatient>[0] = {}): void {
    gateway.getCalls[0]?.resolve(samplePatient({ medicalRecordNumber: 'MRN-88213', ...overrides }));
  }

  it('requests the patient and the encounters for the routed MRN', async () => {
    expect(gateway.getCalls[0]?.request).toBe('MRN-88213');
    expect(gateway.encounterCalls[0]?.request).toBe('MRN-88213');
  });

  it('shows a loading state before anything has arrived', async () => {
    expect((await render()).textContent).toContain('Loading patient record');
  });

  it('renders demographics once the patient arrives', async () => {
    resolvePatient({ familyName: 'Luna', givenName: 'Ixequi', administrativeSex: 'M' });

    const element = await render();
    expect(element.querySelector('h1')?.textContent).toContain('Ixequi Luna');
    expect(element.textContent).toContain('MRN-88213');
    // The code is shown alongside the expansion: the expansion alone is unreadable against a
    // raw HL7 message during an incident, and the code alone is unreadable to everyone else.
    expect(element.textContent).toContain('Male');
    expect(element.textContent).toContain('(M)');
  });

  it('renders a partial birth date exactly as transmitted', async () => {
    // HL7 permits partial dates and the ingest widens rather than pads them. A date
    // formatter would render `1974` as "1 January 1974" — a birthday this system was never
    // told, which then looks authoritative and gets copied somewhere it matters.
    resolvePatient({ birthDate: '1974' });
    expect((await render()).textContent).toContain('1974');
    expect((await render()).textContent).not.toContain('January');
  });

  it('lists identifiers with their assigning authority and HL7 type', async () => {
    resolvePatient({
      identifiers: [
        { system: 'HGS', value: 'MRN-88213', type: 'MR' },
        { system: 'IMSS', value: 'NSS-4471120', type: 'SS' },
      ],
    });

    const items = (await render()).querySelectorAll('.identifier');
    expect(items).toHaveLength(2);
    expect(items[1]?.textContent).toContain('IMSS');
    expect(items[1]?.textContent).toContain('NSS-4471120');
  });

  it('renders encounters in the order the gateway returned them', async () => {
    resolvePatient();
    gateway.encounterCalls[0]?.resolve([
      sampleEncounter({ visitNumber: 'VN-NEW', admittedAt: '2026-08-25T14:30:00.000Z' }),
      sampleEncounter({ visitNumber: 'VN-OLD', admittedAt: '2025-01-04T09:00:00.000Z' }),
    ]);

    // Ordering is the gateway's contract, not the component's. Re-sorting here would mean
    // every consumer re-derives "newest first" and one of them eventually gets it wrong.
    const visits = [...(await render()).querySelectorAll('.encounter code')].map(
      (el) => el.textContent,
    );
    expect(visits).toEqual(['VN-NEW', 'VN-OLD']);
  });

  it('renders a partial location without empty separators', async () => {
    resolvePatient();
    gateway.encounterCalls[0]?.resolve([
      sampleEncounter({
        location: { pointOfCare: 'URG', room: '', bed: '', facility: 'HGS_PUEBLA' },
      }),
    ]);

    const element = await render();
    // "URG ·  · " reads as data the console failed to load rather than data the sender never
    // had — an emergency encounter routinely carries a point of care and nothing else.
    expect(element.textContent).toContain('URG');
    expect(element.textContent).not.toContain('URG ·  ·');
  });

  it('distinguishes a patient with no visits from a patient that does not exist', async () => {
    resolvePatient();
    gateway.encounterCalls[0]?.resolve([]);

    const element = await render();
    expect(element.textContent).toContain('No encounters recorded');
    expect(element.textContent).not.toContain('Patient not found');
  });

  describe('not found', () => {
    beforeEach(() => {
      gateway.getCalls[0]?.fail(gatewayError('not-found', 'no patient with that MRN'));
    });

    it('renders a dedicated not-found state', async () => {
      const element = await render();
      expect(element.textContent).toContain('Patient not found');
      expect(element.textContent).toContain('MRN-88213');
    });

    it('offers no retry, because the answer will not change', async () => {
      expect((await render()).querySelector('[role="alert"]')).toBeNull();
    });

    it('explains why a real patient can still be missing from this index', async () => {
      expect((await render()).textContent).toContain('until an ADT message arrives');
    });
  });

  it('reports a transient failure as an error, not as a missing patient', async () => {
    // Rendering "patient not found" for a 503 tells someone a record does not exist when it
    // may well.
    gateway.getCalls[0]?.fail(gatewayError('unavailable', 'gateway draining'));

    const element = await render();
    expect(element.textContent).not.toContain('Patient not found');
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('could not be loaded');
    expect(element.querySelector('[role="alert"] button')).not.toBeNull();
  });

  it('still shows demographics when only the encounter query fails', async () => {
    // The reason these are two resources rather than one `forkJoin`: a partial outage should
    // not blank a page whose useful half loaded.
    resolvePatient({ familyName: 'Luna', givenName: 'Ixequi' });
    gateway.encounterCalls[0]?.fail(gatewayError('unavailable', 'encounter stream timed out'));

    const element = await render();
    expect(element.querySelector('h1')?.textContent).toContain('Ixequi Luna');
    expect(element.textContent).toContain('Encounters could not be loaded');
  });

  it('re-fetches when the routed MRN changes', async () => {
    // A signal input plus `rxResource` means navigating between patients re-fetches with no
    // `switchMap` over route params and no manual re-subscription.
    resolvePatient();
    fixture.componentRef.setInput('mrn', 'MRN-10042');
    fixture.detectChanges();

    expect(gateway.getCalls).toHaveLength(2);
    expect(gateway.getCalls[1]?.request).toBe('MRN-10042');
  });
});
