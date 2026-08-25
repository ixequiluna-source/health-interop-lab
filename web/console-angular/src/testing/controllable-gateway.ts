import { Observable, Subscriber } from 'rxjs';

import {
  GatewayError,
  PatientGateway,
  type SearchPatientsRequest,
} from '../app/data/patient-gateway';
import type { Encounter, Patient, PatientPage } from '../app/domain/patient';
import type { PipelineStatus } from '../app/domain/pipeline';

/**
 * A pending call the test controls the completion of.
 *
 * `cancelled` is the load-bearing field. It records whether the observable was torn down
 * before it produced a value, which is what "the stale request was aborted" means in RxJS
 * terms and is the thing the stale-response tests actually assert.
 */
export interface PendingCall<T> {
  readonly request: SearchPatientsRequest | string | null;
  /** Emits a value and completes. A no-op if the call was already cancelled. */
  resolve(value: T): void;
  fail(error: unknown): void;
  readonly cancelled: boolean;
  readonly settled: boolean;
}

class Pending<T> implements PendingCall<T> {
  private subscriber: Subscriber<T> | null = null;
  private settledFlag = false;
  private cancelledFlag = false;

  constructor(readonly request: SearchPatientsRequest | string | null) {}

  attach(subscriber: Subscriber<T>): () => void {
    this.subscriber = subscriber;
    // The teardown runs both on unsubscribe and on completion, so "was it cancelled" is
    // "was it torn down without having settled". Checking `subscriber.closed` instead would
    // report true in both cases and the distinction the tests need would be lost.
    return () => {
      if (!this.settledFlag) {
        this.cancelledFlag = true;
      }
    };
  }

  resolve(value: T): void {
    this.settledFlag = true;
    // Deliberately not guarded on `cancelled`. A test must be able to deliver a response to
    // a call that was already superseded — that is the entire out-of-order scenario. RxJS
    // drops it at the closed subscriber, which is the behaviour under test.
    this.subscriber?.next(value);
    this.subscriber?.complete();
  }

  fail(error: unknown): void {
    this.settledFlag = true;
    this.subscriber?.error(error);
  }

  get cancelled(): boolean {
    return this.cancelledFlag;
  }

  get settled(): boolean {
    return this.settledFlag;
  }
}

/**
 * A `PatientGateway` whose every call stays pending until the test says otherwise.
 *
 * This exists because the interesting behaviour of the search store is *ordering*, and
 * ordering cannot be exercised against a gateway that answers on its own schedule. With
 * every call parked, a test can start two searches and then answer them in the wrong order —
 * which is the race a search box loses in production and the one thing a fixture returning
 * `of(page)` can never reproduce.
 */
export class ControllableGateway extends PatientGateway {
  readonly searchCalls: Pending<PatientPage>[] = [];
  readonly getCalls: Pending<Patient>[] = [];
  readonly encounterCalls: Pending<readonly Encounter[]>[] = [];
  readonly pipelineCalls: Pending<PipelineStatus>[] = [];

  searchPatients(request: SearchPatientsRequest): Observable<PatientPage> {
    return this.park(this.searchCalls, request);
  }

  getPatient(medicalRecordNumber: string): Observable<Patient> {
    return this.park(this.getCalls, medicalRecordNumber);
  }

  listEncounters(medicalRecordNumber: string): Observable<readonly Encounter[]> {
    return this.park(this.encounterCalls, medicalRecordNumber);
  }

  pipelineStatus(): Observable<PipelineStatus> {
    return this.park(this.pipelineCalls, null);
  }

  private park<T>(
    sink: Pending<T>[],
    request: SearchPatientsRequest | string | null,
  ): Observable<T> {
    // Constructed inside the `Observable` factory, so the call is only recorded when someone
    // actually subscribes. Registering it at call time would count requests the store built
    // but never issued.
    return new Observable<T>((subscriber) => {
      const pending = new Pending<T>(request);
      sink.push(pending);
      return pending.attach(subscriber);
    });
  }
}

/** A minimal, valid patient. Overrides only what a test cares about. */
export function samplePatient(overrides: Partial<Patient> = {}): Patient {
  return {
    medicalRecordNumber: 'MRN-1',
    identifiers: [{ system: 'HGS', value: 'MRN-1', type: 'MR' }],
    familyName: 'García',
    givenName: 'María',
    birthDate: '1984-11-02',
    administrativeSex: 'F',
    lastUpdated: '2026-08-25T14:30:00.000Z',
    ...overrides,
  };
}

export function samplePage(overrides: Partial<PatientPage> = {}): PatientPage {
  return {
    patients: [samplePatient()],
    nextPageToken: null,
    totalMatched: 1,
    ...overrides,
  };
}

export function sampleEncounter(overrides: Partial<Encounter> = {}): Encounter {
  return {
    visitNumber: 'VN-1',
    medicalRecordNumber: 'MRN-1',
    patientClass: 'I',
    admittedAt: '2026-08-25T14:30:00.000Z',
    attendingClinician: 'Enrique Torres',
    location: { pointOfCare: 'WARD-3', room: '301', bed: 'A', facility: 'HGS_PUEBLA' },
    ...overrides,
  };
}

export function gatewayError(
  kind: GatewayError['kind'] = 'unavailable',
  message = 'gateway is unavailable',
): GatewayError {
  return new GatewayError(kind, message, 'a1b2c3d4e5f60718293a4b5c6d7e8f90');
}
