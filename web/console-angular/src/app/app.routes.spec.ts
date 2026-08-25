import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { ControllableGateway, samplePatient } from '../testing/controllable-gateway';
import { routes } from './app.routes';
import { ENVIRONMENT, type AppEnvironment } from './core/environment';
import { PatientGateway } from './data/patient-gateway';
import { PatientDetailPage } from './features/patient-detail/patient-detail';
import { PatientSearchPage } from './features/patient-search/patient-search';
import { NotFoundPage } from './features/not-found/not-found';

const TEST_ENVIRONMENT: AppEnvironment = {
  label: 'spec',
  gateway: 'in-memory',
  gatewayBaseUrl: '',
  pipelinePollMs: 30_000,
};

describe('routing', () => {
  let gateway: ControllableGateway;

  beforeEach(() => {
    gateway = new ControllableGateway();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter(routes, withComponentInputBinding()),
        { provide: PatientGateway, useValue: gateway },
        { provide: ENVIRONMENT, useValue: TEST_ENVIRONMENT },
      ],
    });
  });

  it('sends the root path to patient search', async () => {
    const harness = await RouterTestingHarness.create('/');
    expect(harness.routeDebugElement?.componentInstance).toBeInstanceOf(PatientSearchPage);
  });

  it('routes to the patient record and binds the MRN as a component input', async () => {
    const harness = await RouterTestingHarness.create();
    const component = await harness.navigateByUrl('/patients/MRN-88213', PatientDetailPage);

    // The end-to-end assertion for `withComponentInputBinding()`: the route parameter becomes
    // a typed signal input, and the component issues its query from it without ever touching
    // `ActivatedRoute`.
    expect(component.mrn()).toBe('MRN-88213');
    expect(gateway.getCalls[0]?.request).toBe('MRN-88213');
  });

  it('re-binds the input when navigating from one patient to another', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/patients/MRN-88213', PatientDetailPage);
    gateway.getCalls[0]?.resolve(samplePatient({ medicalRecordNumber: 'MRN-88213' }));

    const component = await harness.navigateByUrl('/patients/MRN-10042', PatientDetailPage);

    // Angular reuses the component instance across a parameter-only navigation, so a
    // component that read the MRN once at construction would keep showing the first patient
    // under the second patient's URL. The signal input plus `rxResource` makes that
    // impossible rather than merely unlikely.
    expect(component.mrn()).toBe('MRN-10042');
    expect(gateway.getCalls).toHaveLength(2);
    expect(gateway.getCalls[1]?.request).toBe('MRN-10042');
  });

  it('percent-decodes an MRN containing reserved characters', async () => {
    const harness = await RouterTestingHarness.create();
    const component = await harness.navigateByUrl('/patients/MRN%2F88213', PatientDetailPage);

    // MRN formats vary by assigning authority and some carry a separator. The router decodes
    // the segment, so the gateway must receive the identifier and not its encoding.
    expect(component.mrn()).toBe('MRN/88213');
  });

  it('keeps the search term out of the URL', async () => {
    const harness = await RouterTestingHarness.create('/patients');
    const router = TestBed.inject(Router);

    const component = harness.routeDebugElement?.componentInstance as PatientSearchPage;
    expect(component).toBeInstanceOf(PatientSearchPage);

    harness.detectChanges();
    const input = (harness.routeNativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '#search-term',
    );
    input!.value = 'García';
    input!.dispatchEvent(new Event('input'));
    harness.detectChanges();

    // A patient name in a URL is recorded by the browser's history, proxy and load-balancer
    // access logs, and the `Referer` of any outbound link — none of which sit inside the
    // retention and access regime the clinical store does. The cost is that a search is not
    // shareable or restorable, which is accepted.
    expect(router.url).toBe('/patients');
    expect(router.url).not.toContain('Garc');
  });

  it('renders a not-found page for an unmatched URL without echoing it', async () => {
    const harness = await RouterTestingHarness.create('/no/such/screen');
    expect(harness.routeDebugElement?.componentInstance).toBeInstanceOf(NotFoundPage);

    // An unmatched URL is attacker-controlled text and the page gains nothing from showing
    // it back.
    expect((harness.routeNativeElement as HTMLElement).textContent).not.toContain('no/such');
  });

  it('gives every route a title that names no patient', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/patients/MRN-88213', PatientDetailPage);
    gateway.getCalls[0]?.resolve(samplePatient({ familyName: 'Luna', givenName: 'Ixequi' }));
    harness.detectChanges();

    // Window titles reach the browser's history, the OS window list, screen-share previews
    // and session-recording tools — the same channels the URL rule is about.
    expect(document.title).toBe('Patient record · Interop console');
    expect(document.title).not.toContain('Luna');
  });
});
