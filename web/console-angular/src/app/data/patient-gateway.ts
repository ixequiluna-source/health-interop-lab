import { Observable } from 'rxjs';

import type { Encounter, Patient, PatientPage } from '../domain/patient';
import type { PipelineStatus } from '../domain/pipeline';

/** Minimum characters before a search is worth issuing. Mirrors `patient.MinQueryLength`. */
export const MIN_QUERY_LENGTH = 2;

/** Server default. Mirrors `patient.DefaultPageSize`. */
export const DEFAULT_PAGE_SIZE = 25;

/**
 * Server cap. Mirrors `patient.MaxPageSize`.
 *
 * Replicated client-side not as validation — the server clamps regardless — but so the UI
 * never offers a page size the server will silently reduce, which otherwise shows up as
 * "I asked for 500 and got 100" with no explanation on screen.
 */
export const MAX_PAGE_SIZE = 100;

export interface SearchPatientsRequest {
  /** Free text, matched against family name, given name and identifiers. */
  readonly query: string;
  readonly pageSize?: number;
  /**
   * An opaque token from a previous `PatientPage.nextPageToken`, or omitted for page one.
   *
   * Tokens are only meaningful for the query that produced them. Carrying one across a
   * changed search term is an error the gateway must reject, not silently honour: honouring
   * it returns an arbitrary slice of a different result set, which on screen looks like
   * correct data for the wrong patients.
   */
  readonly pageToken?: string;
}

/**
 * Errors the UI is expected to distinguish.
 *
 * A discriminated union rather than status codes, because the console's decision is not
 * "which HTTP code was it" but "what do I show and can the operator retry". The transport
 * mapping lives in exactly one place (`HttpPatientGateway`), which is what keeps
 * `catch (e) { if (e.status === 404) }` out of the components.
 */
export type GatewayErrorKind =
  | 'not-found'
  /** The request was rejected as malformed — short query, stale page token. Not retryable. */
  | 'invalid-request'
  /** Reached the proxy, the proxy or the gateway failed. Retryable. */
  | 'unavailable'
  /** Network never completed: offline, DNS, TLS, CORS preflight. Retryable. */
  | 'network'
  | 'unknown';

export class GatewayError extends Error {
  constructor(
    readonly kind: GatewayErrorKind,
    message: string,
    /**
     * The `traceparent` this request carried, when one was attached.
     *
     * Surfacing it in the UI is the entire point of the trace interceptor: an operator can
     * read the id off the error panel and hand it to whoever owns the backend, and the
     * whole call — console, proxy, gateway, Mongo — is one query away. Without it, "search
     * was broken around 14:30" is the bug report.
     */
    readonly traceId: string | null = null,
    options?: { cause?: unknown },
  ) {
    super(message, options);
    this.name = 'GatewayError';
  }
}

/**
 * Every read this console performs.
 *
 * ## Why this is an abstract class and not just an interface
 *
 * It is the Angular DI token as well as the contract. An `InjectionToken<PatientGateway>`
 * would work equally well but costs a separate symbol that has to be kept in sync with the
 * interface; an abstract class is one declaration that is both. `providedIn` is deliberately
 * absent — the implementation is chosen in `app.config.ts` from the environment, so there is
 * no default that can be injected by accident.
 *
 * ## Why the abstraction exists at all
 *
 * The backing service is gRPC, which a browser cannot speak: gRPC needs trailers and control
 * over HTTP/2 framing that `fetch` does not expose. Reaching it requires a gRPC-Web or
 * Connect proxy in front, which is an extra deployment. Rather than let that fact leak into
 * the UI — or, worse, stub the UI against fixtures that behave nothing like the API — the
 * data access sits behind this one seam with two full implementations:
 *
 * - `HttpPatientGateway` talks proto3 JSON to a Connect proxy.
 * - `InMemoryPatientGateway` serves seeded data and implements the *same* pagination
 *   contract — opaque tokens, stable total ordering, tokens bound to their query.
 *
 * The in-memory one is a real implementation rather than a stub precisely so that developing
 * against it exercises the UI honestly. A fixture that returns every patient in one array
 * never shows you that your "load more" button appends duplicates when two patients share a
 * surname, because a fixture has no ordering rules to get wrong.
 *
 * ## Why the search term is not a URL parameter anywhere in this contract
 *
 * `SearchPatientsRequest.query` is a patient name. It travels in a POST body. It must never
 * become a query string, a route parameter or a fragment, because URLs are recorded in
 * places clinical data is not: proxy and load-balancer access logs, browser history, the
 * `Referer` header on any outbound link, and the address bar during a screen share. Page
 * tokens are subject to the same rule — the Go gateway stores a hash of the search term in
 * the cursor rather than the term itself for exactly this reason.
 */
export abstract class PatientGateway {
  /** Rejects with `GatewayError('not-found')` when no patient carries the MRN. */
  abstract getPatient(medicalRecordNumber: string): Observable<Patient>;

  /**
   * One page of matches.
   *
   * Rejects with `GatewayError('invalid-request')` for a query below
   * `MIN_QUERY_LENGTH` or a page token belonging to a different query.
   */
  abstract searchPatients(request: SearchPatientsRequest): Observable<PatientPage>;

  /**
   * A patient's visits, newest first.
   *
   * Ordering is the server's responsibility, not the component's. Sorting in the template
   * means every consumer re-derives "newest first" and one of them eventually gets it
   * wrong — and a mis-sorted encounter list reads as a patient who was discharged before
   * they were admitted.
   */
  abstract listEncounters(medicalRecordNumber: string): Observable<readonly Encounter[]>;

  /** A point-in-time health and counter snapshot of the pipeline. */
  abstract pipelineStatus(): Observable<PipelineStatus>;
}
