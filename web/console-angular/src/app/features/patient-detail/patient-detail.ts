import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { GatewayError, PatientGateway } from '../../data/patient-gateway';
import { fullName, type Encounter, type Patient } from '../../domain/patient';

/**
 * HL7 table 0004 patient classes, spelled out.
 *
 * The console shows both — the code, because it is what appears in the message and in every
 * other system, and the expansion, because "P" is not self-explanatory to anyone who has not
 * memorised the table. Showing only the expansion would make the screen impossible to
 * reconcile against a raw message during an incident; showing only the code makes it
 * unreadable to everyone else.
 */
const PATIENT_CLASS_LABELS: Readonly<Record<string, string>> = {
  E: 'Emergency',
  I: 'Inpatient',
  O: 'Outpatient',
  P: 'Preadmit',
  R: 'Recurring patient',
  B: 'Obstetrics',
  C: 'Commercial account',
  N: 'Not applicable',
  U: 'Unknown',
};

/** HL7 table 0001 administrative sex. */
const ADMINISTRATIVE_SEX_LABELS: Readonly<Record<string, string>> = {
  F: 'Female',
  M: 'Male',
  O: 'Other',
  U: 'Unknown',
  A: 'Ambiguous',
  N: 'Not applicable',
};

@Component({
  selector: 'app-patient-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, DatePipe],
  templateUrl: './patient-detail.html',
  styleUrl: './patient-detail.css',
})
export class PatientDetailPage {
  private readonly gateway = inject(PatientGateway);

  /**
   * The MRN from the route, delivered by `withComponentInputBinding()`.
   *
   * A signal input rather than `inject(ActivatedRoute).paramMap`. Two things follow. The
   * resources below depend on it directly, so navigating from one patient to another
   * re-fetches without any `switchMap` over params or manual re-subscription. And a test
   * sets `fixture.componentRef.setInput('mrn', …)` instead of constructing a fake route
   * snapshot — the difference between testing this component and testing the router.
   *
   * `input.required` because a route that reaches this component without an `:mrn` segment
   * is a routing bug, and failing loudly at construction is better than rendering a page
   * that queries for `undefined`.
   */
  readonly mrn = input.required<string>();

  /**
   * Demographics.
   *
   * `rxResource` rather than a hand-rolled signal-plus-subscription. It is marked
   * experimental in Angular 21, which is a real consideration for production code, and the
   * containment is deliberate: it appears only in this component and in the pipeline
   * dashboard, both times behind the `PatientGateway` abstraction, and the replacement if
   * the API changes is roughly fifteen lines of `toSignal` plus a `switchMap`. That is a
   * bounded, known cost, and in exchange the loading, error and reload states come from one
   * place instead of from four signals that have to be kept consistent by hand.
   *
   * It also cancels correctly: changing `params` aborts the in-flight request, which is the
   * same stale-response protection the search store gets from `switchMap`.
   */
  protected readonly patientResource = rxResource<Patient, string>({
    params: () => this.mrn(),
    stream: ({ params }) => this.gateway.getPatient(params),
  });

  /**
   * Visits, newest first — ordered by the gateway, not here.
   *
   * A second resource rather than one combined request. Demographics and the visit list fail
   * independently: a patient who exists but whose encounter query times out should still
   * render a header with a name and an MRN, because that is often all the operator needed.
   * Combining them with `forkJoin` would collapse both into a single failure and blank the
   * page for a partial outage.
   *
   * `defaultValue` keeps the value signal off `undefined`, so the template branches on
   * status rather than on the shape of the value.
   */
  protected readonly encountersResource = rxResource<readonly Encounter[], string>({
    params: () => this.mrn(),
    stream: ({ params }) => this.gateway.listEncounters(params),
    defaultValue: [],
  });

  protected readonly fullName = fullName;

  /**
   * Whether the failure was specifically "no such patient".
   *
   * Distinguished from every other error because the two are not the same page. A missing
   * patient is a final answer that the operator should act on by checking the MRN; an
   * unavailable gateway is a transient condition with a retry. Rendering "patient not found"
   * for a 503 tells someone a record does not exist when it may well.
   */
  protected readonly isNotFound = computed(() => {
    const error = this.patientResource.error();
    return error instanceof GatewayError && error.kind === 'not-found';
  });

  protected readonly patientError = computed(() => {
    const error = this.patientResource.error();
    return error instanceof GatewayError ? error : null;
  });

  protected readonly encountersError = computed(() => {
    const error = this.encountersResource.error();
    return error instanceof GatewayError ? error : null;
  });

  protected patientClassLabel(code: string): string {
    return PATIENT_CLASS_LABELS[code] ?? 'Unrecognised class';
  }

  protected administrativeSexLabel(code: string): string {
    return ADMINISTRATIVE_SEX_LABELS[code] ?? 'Unrecognised code';
  }

  /**
   * Renders a location as "WARD-3 · 301 · A", omitting the parts that are absent.
   *
   * Emergency and preadmit encounters routinely carry a point of care and nothing else, and
   * joining unconditionally would render "URG ·  · " — which looks like data the console
   * failed to load rather than data the sender never had.
   */
  protected locationLabel(encounter: Encounter): string {
    const parts = [
      encounter.location.pointOfCare,
      encounter.location.room,
      encounter.location.bed,
    ].filter((part) => part !== '');
    return parts.length === 0 ? 'Not recorded' : parts.join(' · ');
  }

  protected onRetryPatient(): void {
    this.patientResource.reload();
  }

  protected onRetryEncounters(): void {
    this.encountersResource.reload();
  }
}
