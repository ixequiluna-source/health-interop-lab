import { Injectable, InjectionToken, inject } from '@angular/core';
import { Observable, of, switchMap, throwError, timer } from 'rxjs';

import { fold, type Encounter, type Patient, type PatientPage } from '../domain/patient';
import type { PipelineStatus } from '../domain/pipeline';
import { decodePageToken, encodePageToken, hashTerm, InvalidPageTokenError } from './page-token';
import {
  DEFAULT_PAGE_SIZE,
  GatewayError,
  MAX_PAGE_SIZE,
  MIN_QUERY_LENGTH,
  PatientGateway,
  type SearchPatientsRequest,
} from './patient-gateway';
import { SAMPLE_ENCOUNTERS, SAMPLE_PATIENTS, SAMPLE_PIPELINE_STATUS } from './sample-data';

/**
 * Artificial latency, in milliseconds, applied to every in-memory call.
 *
 * A gateway that answers synchronously is not a useful stand-in. Every loading state, every
 * debounce window and every stale-response guard in this console only has behaviour to
 * exercise if a response takes measurable time; with a zero-latency fake, the loading
 * skeleton is never rendered in development and the first time anyone sees it is in
 * production, where it is also the first time anyone notices it is wrong.
 *
 * Injectable so tests can pin it — `{ provide: IN_MEMORY_LATENCY_MS, useValue: 0 }` — rather
 * than every spec having to `tick(180)` past a number it does not care about.
 */
export const IN_MEMORY_LATENCY_MS = new InjectionToken<number>('IN_MEMORY_LATENCY_MS', {
  providedIn: 'root',
  factory: () => 180,
});

/**
 * A `PatientGateway` backed by seeded data, used when no Connect proxy is reachable.
 *
 * ## Why this is a full implementation and not a stub
 *
 * The pagination rules are where a patient search actually goes wrong, and they are exactly
 * what a stub omits. This class reproduces all three:
 *
 * 1. **A total order over results.** Matching alone is not enough; two pages of the same
 *    search must not repeat or omit a patient. With surnames as repetitive as this data set,
 *    sorting on family name alone leaves ties in whatever order the array happened to be in,
 *    and the boundary between page one and page two then lands mid-tie. The sort is
 *    therefore (folded family, folded given, MRN) — terminating on MRN, which is unique.
 * 2. **Tokens bound to their query.** A cursor is only meaningful for the result set that
 *    produced it. Reusing one across a changed term returns a slice of a different set,
 *    which renders as plausible rows for the wrong patients.
 * 3. **`nextPageToken` as the sole end-of-results signal.** Absent on the last page, present
 *    otherwise — never inferred from page length.
 *
 * ## Why it is not a `MemoryStore` port
 *
 * It intentionally does not share code with the Go `patient.MemoryStore`. It reimplements
 * the same contract from the same written rules, which is what makes the two an actual
 * cross-check: a shared implementation would agree with itself even when both were wrong.
 */
@Injectable()
export class InMemoryPatientGateway extends PatientGateway {
  private readonly latencyMs = inject(IN_MEMORY_LATENCY_MS);

  private readonly patients: readonly Patient[] = SAMPLE_PATIENTS;
  private readonly encounters: readonly Encounter[] = SAMPLE_ENCOUNTERS;

  getPatient(medicalRecordNumber: string): Observable<Patient> {
    return this.respond(() => {
      const mrn = medicalRecordNumber.trim();
      if (mrn === '') {
        throw new GatewayError('invalid-request', 'medical record number is required');
      }
      const patient = this.patients.find((p) => p.medicalRecordNumber === mrn);
      if (patient === undefined) {
        // Exact match only. Folding the MRN the way names are folded would let `mrn-88213`
        // resolve to a different patient's record than `MRN-88213` on a system where the
        // two are distinct identifiers — identifier matching must be exact by construction.
        throw new GatewayError('not-found', `no patient with medical record number ${mrn}`);
      }
      return patient;
    });
  }

  searchPatients(request: SearchPatientsRequest): Observable<PatientPage> {
    return this.respond(() => {
      const term = request.query.trim();
      if ([...term].length < MIN_QUERY_LENGTH) {
        throw new GatewayError(
          'invalid-request',
          `query must be at least ${MIN_QUERY_LENGTH} characters`,
        );
      }

      // Zero means "unspecified" and takes the default; over the cap is clamped rather than
      // rejected, because a client asking for too much should still get a useful answer.
      const requested = request.pageSize ?? 0;
      if (requested < 0) {
        throw new GatewayError('invalid-request', 'page size cannot be negative');
      }
      const pageSize = requested === 0 ? DEFAULT_PAGE_SIZE : Math.min(requested, MAX_PAGE_SIZE);

      const offset = this.resolveOffset(term, request.pageToken);
      const matched = this.matchesFor(term);
      const slice = matched.slice(offset, offset + pageSize);
      const end = offset + slice.length;

      return {
        patients: slice,
        totalMatched: matched.length,
        // Present only when results remain. Note this is `end < total`, not
        // `slice.length === pageSize`: the two differ exactly when a page happens to end on
        // the final record, and the second form mints a token for an empty page.
        nextPageToken: end < matched.length ? encodePageToken(term, end) : null,
      } satisfies PatientPage;
    });
  }

  listEncounters(medicalRecordNumber: string): Observable<readonly Encounter[]> {
    return this.respond(() => {
      const mrn = medicalRecordNumber.trim();
      if (!this.patients.some((p) => p.medicalRecordNumber === mrn)) {
        // A patient with no visits and a patient who does not exist are different answers.
        // Returning an empty list for an unknown MRN would render a detail page for a
        // patient this system has never seen.
        throw new GatewayError('not-found', `no patient with medical record number ${mrn}`);
      }
      // RFC 3339 strings produced with the same offset sort lexicographically the same way
      // they sort chronologically, so no `Date` parsing is needed — and none is done,
      // because parsing and re-comparing is where a time zone gets introduced into an
      // ordering that did not have one.
      return [...this.encounters]
        .filter((e) => e.medicalRecordNumber === mrn)
        .sort((a, b) => b.admittedAt.localeCompare(a.admittedAt));
    });
  }

  pipelineStatus(): Observable<PipelineStatus> {
    return this.respond(() => SAMPLE_PIPELINE_STATUS);
  }

  /** Every match for `term`, in the total order pagination depends on. */
  private matchesFor(term: string): readonly Patient[] {
    const needle = fold(term);
    if (needle === '') {
      return [];
    }
    return this.patients
      .filter((patient) => {
        const haystacks = [
          patient.familyName,
          patient.givenName,
          patient.medicalRecordNumber,
          ...patient.identifiers.map((id) => id.value),
        ];
        return haystacks.some((value) => fold(value).includes(needle));
      })
      .sort((a, b) => {
        const family = fold(a.familyName).localeCompare(fold(b.familyName));
        if (family !== 0) {
          return family;
        }
        const given = fold(a.givenName).localeCompare(fold(b.givenName));
        if (given !== 0) {
          return given;
        }
        // MRN is unique, so this terminates the comparison and the order is total.
        return a.medicalRecordNumber.localeCompare(b.medicalRecordNumber);
      });
  }

  private resolveOffset(term: string, pageToken: string | undefined): number {
    if (pageToken === undefined || pageToken === '') {
      return 0;
    }
    try {
      const cursor = decodePageToken(pageToken);
      if (cursor.termHash !== hashTerm(term)) {
        throw new GatewayError('invalid-request', 'page token belongs to a different query');
      }
      return cursor.offset;
    } catch (cause) {
      if (cause instanceof GatewayError) {
        throw cause;
      }
      if (cause instanceof InvalidPageTokenError) {
        throw new GatewayError('invalid-request', 'page token is not valid', null, { cause });
      }
      throw cause;
    }
  }

  /**
   * Runs `produce` at subscription time, after the configured latency.
   *
   * `timer(...).pipe(switchMap(...))` rather than `of(...).pipe(delay(...))` because RxJS
   * `delay` forwards *error* notifications immediately and only delays values. With `delay`,
   * a rejected page token would come back synchronously while a successful one took 180ms —
   * so the error path would be the one path never exercised against realistic timing, and
   * an error state that renders under a still-visible loading spinner would ship.
   *
   * Building the value inside the pipeline also means the `throw` in `produce` becomes an
   * error notification rather than a synchronous throw. Callers therefore handle failures in
   * exactly one place regardless of which gateway is wired in: the HTTP implementation
   * cannot throw synchronously, and neither can this one.
   */
  private respond<T>(produce: () => T): Observable<T> {
    return timer(this.latencyMs).pipe(
      switchMap(() => {
        try {
          return of(produce());
        } catch (error) {
          return throwError(() => error);
        }
      }),
    );
  }
}
