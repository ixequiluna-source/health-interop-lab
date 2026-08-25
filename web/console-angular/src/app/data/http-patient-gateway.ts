import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, throwError } from 'rxjs';

import { TRACE_ID } from '../core/trace-context';
import { ENVIRONMENT } from '../core/environment';
import type { Encounter, Patient, PatientPage } from '../domain/patient';
import type { PipelineStatus } from '../domain/pipeline';
import {
  GatewayError,
  PatientGateway,
  type GatewayErrorKind,
  type SearchPatientsRequest,
} from './patient-gateway';

/**
 * `PatientGateway` over HTTP, talking to a Connect proxy in front of the Go gRPC gateway.
 *
 * ## The proxy, and why there is one
 *
 * A browser cannot speak gRPC. gRPC requires HTTP/2 trailers and control over framing that
 * `fetch` and `XMLHttpRequest` do not expose, and no amount of client-side work changes
 * that. Reaching `interop.v1.PatientService` from a page therefore requires a translating
 * proxy — Envoy's gRPC-Web filter, `connect-go`'s handler, or `grpcwebproxy`. This class
 * assumes the **Connect protocol's unary JSON mode**, which is the plainest of those wires:
 *
 * ```
 * POST /interop.v1.PatientService/SearchPatients
 * Content-Type: application/json
 * {"query":"garcia","pageSize":25}
 * ```
 *
 * with the proto3 JSON encoding of the response on 200, and a `{"code","message"}` body on
 * failure. Plain JSON over POST means `HttpClient` handles it with no client runtime, no
 * generated stubs and no base64 framing — the same reason the domain types are hand-mirrored
 * rather than generated.
 *
 * ## Why every call is a POST, including the reads
 *
 * Two independent reasons, and the second is the one that matters.
 *
 * 1. Connect's unary protocol is POST-based; GET is an optional extension that requires
 *    server opt-in and only applies to methods marked idempotent.
 * 2. **The search term is a patient name.** In a POST body it stays in the request payload.
 *    As a query string it would be written into the proxy's access log, the load balancer's
 *    log, the browser's history and address bar, and the `Referer` of any outbound link —
 *    none of which sit inside the retention and access controls the clinical store does.
 *    That is a disclosure of PHI through a side channel, and it is the kind that survives
 *    for as long as log retention does. The same rule is why page tokens carry a hash of the
 *    search term rather than the term, and why this console keeps the search box out of the
 *    router URL entirely.
 *
 * ## Why `StreamEncounters` is called as a unary `ListEncounters`
 *
 * The proto declares `StreamEncounters` as server-streaming. Connect encodes streams as
 * length-prefixed envelopes, which `HttpClient` cannot decode incrementally — consuming them
 * means `responseType: 'arraybuffer'`, manual 5-byte framing, and a hand-rolled
 * backpressure story. That work buys nothing here: the detail page renders the complete
 * encounter list, sorted, with a count. It has no incremental display to drive.
 *
 * So the proxy exposes a unary `ListEncounters` that collects the stream server-side, and
 * this console calls that. The streaming RPC stays in the proto for consumers that do have
 * something to do with a partial list — the ADT reconciliation job reads it that way.
 */
@Injectable()
export class HttpPatientGateway extends PatientGateway {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(ENVIRONMENT).gatewayBaseUrl.replace(/\/+$/, '');

  getPatient(medicalRecordNumber: string): Observable<Patient> {
    return this.call<{ patient?: PatientJson }, Patient>(
      'GetPatient',
      { medicalRecordNumber },
      (response) => {
        // proto3 JSON omits message fields that were never set, so a `GetPatientResponse`
        // whose `patient` is absent is well-formed JSON and a broken response. Treating the
        // absence as `not-found` rather than letting `undefined` reach the template is the
        // difference between a handled state and a detail page of empty rows.
        if (response.patient === undefined) {
          throw new GatewayError('not-found', 'response contained no patient');
        }
        return decodePatient(response.patient);
      },
    );
  }

  searchPatients(request: SearchPatientsRequest): Observable<PatientPage> {
    // Built explicitly rather than spread from `request`, so that an added field on
    // `SearchPatientsRequest` is a compile-time decision about the wire rather than
    // something that silently starts being transmitted.
    const body: Record<string, unknown> = { query: request.query };
    if (request.pageSize !== undefined) {
      body['pageSize'] = request.pageSize;
    }
    if (request.pageToken !== undefined && request.pageToken !== '') {
      // Forwarded verbatim. The console does not parse, validate or reconstruct page tokens;
      // they are the server's private encoding and it must stay free to change them.
      body['pageToken'] = request.pageToken;
    }

    return this.call<SearchPatientsResponseJson, PatientPage>(
      'SearchPatients',
      body,
      (response) => ({
        patients: (response.patients ?? []).map(decodePatient),
        // proto3 JSON omits default values, so an absent `nextPageToken` and an absent
        // `totalMatched` both mean the proto default. `?? null` and `?? 0` are the decoding
        // rule, not defensive padding — a server that has no more results genuinely sends
        // no `nextPageToken` field at all.
        nextPageToken: response.nextPageToken ?? null,
        totalMatched: response.totalMatched ?? 0,
      }),
    );
  }

  listEncounters(medicalRecordNumber: string): Observable<readonly Encounter[]> {
    return this.call<{ encounters?: EncounterJson[] }, readonly Encounter[]>(
      'ListEncounters',
      { medicalRecordNumber },
      (response) => (response.encounters ?? []).map(decodeEncounter),
    );
  }

  /**
   * Pipeline health.
   *
   * Not a Connect RPC: it is served by the console's own edge, which aggregates the
   * `/healthz` probes of the five services. Health belongs to the deployment rather than to
   * `PatientService`, and adding a `GetPipelineStatus` method to a patient-read service
   * would put an operational concern in a clinical API — where it would then be part of the
   * contract every future consumer of patient data has to reason about.
   */
  pipelineStatus(): Observable<PipelineStatus> {
    const context = new HttpContext();
    return this.http
      .get<PipelineStatus>(`${this.baseUrl}/ops/pipeline-status`, { context })
      .pipe(catchError((error: unknown) => throwError(() => this.toGatewayError(error, context))));
  }

  /**
   * One Connect unary call, decoded.
   *
   * `decode` is a parameter rather than a `map` the callers append afterwards, and that is
   * not a stylistic choice. A `map` applied outside this method sits *downstream* of the
   * `catchError` below, so an error raised while decoding — the `not-found` synthesised from
   * an empty `GetPatientResponse`, for one — escapes without ever passing through
   * `toGatewayError` and arrives at the UI with no trace id on it. Taking the decoder here
   * puts it inside the same pipe, so every failure of the call, transport or decoding, is
   * mapped in one place.
   */
  private call<TResponse, TResult>(
    method: string,
    body: Record<string, unknown>,
    decode: (response: TResponse) => TResult,
  ): Observable<TResult> {
    // A fresh `HttpContext` per call, constructed here so the trace interceptor has
    // somewhere to write the id it mints and this method can read it back on failure.
    const context = new HttpContext();
    return this.http
      .post<TResponse>(`${this.baseUrl}/interop.v1.PatientService/${method}`, body, { context })
      .pipe(
        map(decode),
        catchError((error: unknown) => throwError(() => this.toGatewayError(error, context))),
      );
  }

  /**
   * Maps a transport failure onto the union the UI branches on.
   *
   * All of this lives here so that no component ever inspects a status code. The mapping is
   * from Connect's error codes first and the HTTP status second, because a Connect proxy
   * reports `not_found` as HTTP 404 but also reports several distinct gRPC codes as 400 —
   * status alone cannot tell `invalid_argument` from `failed_precondition`.
   */
  private toGatewayError(error: unknown, context: HttpContext): GatewayError {
    const traceId = context.get(TRACE_ID);

    if (error instanceof GatewayError) {
      // Thrown from a `map` above — already the right shape, but minted before the trace id
      // was readable, so re-issue it with the id attached.
      return new GatewayError(error.kind, error.message, traceId, { cause: error.cause });
    }

    if (!(error instanceof HttpErrorResponse)) {
      return new GatewayError('unknown', 'unexpected failure', traceId, { cause: error });
    }

    // `status === 0` is Angular's marker for a request that never produced a response:
    // offline, DNS failure, TLS rejection, or a CORS preflight the proxy refused. It is
    // reported separately because the operator action differs — "check your connection"
    // versus "the backend is unhealthy" — and because a misconfigured `Access-Control-
    // Allow-Headers` for `traceparent` surfaces here and nowhere else.
    if (error.status === 0) {
      return new GatewayError('network', 'the gateway could not be reached', traceId, {
        cause: error,
      });
    }

    const connect = asConnectError(error.error);
    const kind =
      connect !== null ? kindForConnectCode(connect.code) : kindForHttpStatus(error.status);
    const message =
      connect?.message ?? `gateway responded ${error.status} ${error.statusText || ''}`.trim();

    return new GatewayError(kind, message, traceId, { cause: error });
  }
}

/** Connect's error envelope: `{"code":"not_found","message":"…"}`. */
interface ConnectErrorBody {
  readonly code: string;
  readonly message?: string;
}

function asConnectError(body: unknown): ConnectErrorBody | null {
  if (typeof body !== 'object' || body === null) {
    return null;
  }
  const candidate = body as { code?: unknown; message?: unknown };
  if (typeof candidate.code !== 'string') {
    return null;
  }
  return {
    code: candidate.code,
    ...(typeof candidate.message === 'string' ? { message: candidate.message } : {}),
  };
}

function kindForConnectCode(code: string): GatewayErrorKind {
  switch (code) {
    case 'not_found':
      return 'not-found';
    case 'invalid_argument':
    case 'failed_precondition':
    case 'out_of_range':
      // All three mean "this request as written will never succeed". Grouping them is the
      // point: the UI's only useful response to any of them is to stop and tell the operator
      // to change the input, and a retry button on a permanently invalid request is worse
      // than no button.
      return 'invalid-request';
    case 'unavailable':
    case 'deadline_exceeded':
    case 'resource_exhausted':
    case 'internal':
    case 'unknown':
      return 'unavailable';
    default:
      return 'unknown';
  }
}

function kindForHttpStatus(status: number): GatewayErrorKind {
  if (status === 404) {
    return 'not-found';
  }
  if (status === 400 || status === 422) {
    return 'invalid-request';
  }
  if (status >= 500 || status === 429 || status === 408) {
    return 'unavailable';
  }
  return 'unknown';
}

/**
 * The wire shapes, kept separate from the domain types.
 *
 * Every field is optional because that is what proto3 JSON actually guarantees: fields at
 * their default value are omitted. Declaring them required and asserting the response into
 * the domain type would compile and then hand the templates `undefined` where they expect a
 * string — which `strictTemplates` cannot catch, because as far as the compiler is concerned
 * the type says otherwise. The `decode*` functions are where the optionality is resolved,
 * once, at the boundary.
 */
interface PatientJson {
  medicalRecordNumber?: string;
  identifiers?: { system?: string; value?: string; type?: string }[];
  familyName?: string;
  givenName?: string;
  birthDate?: string;
  administrativeSex?: string;
  lastUpdated?: string;
}

interface EncounterJson {
  visitNumber?: string;
  medicalRecordNumber?: string;
  patientClass?: string;
  admittedAt?: string;
  attendingClinician?: string;
  location?: { pointOfCare?: string; room?: string; bed?: string; facility?: string };
}

interface SearchPatientsResponseJson {
  patients?: PatientJson[];
  nextPageToken?: string;
  totalMatched?: number;
}

function decodePatient(json: PatientJson): Patient {
  return {
    medicalRecordNumber: json.medicalRecordNumber ?? '',
    identifiers: (json.identifiers ?? []).map((id) => ({
      system: id.system ?? '',
      value: id.value ?? '',
      type: id.type ?? '',
    })),
    familyName: json.familyName ?? '',
    givenName: json.givenName ?? '',
    birthDate: json.birthDate ?? '',
    administrativeSex: json.administrativeSex ?? '',
    lastUpdated: json.lastUpdated ?? '',
  };
}

function decodeEncounter(json: EncounterJson): Encounter {
  return {
    visitNumber: json.visitNumber ?? '',
    medicalRecordNumber: json.medicalRecordNumber ?? '',
    patientClass: json.patientClass ?? '',
    admittedAt: json.admittedAt ?? '',
    attendingClinician: json.attendingClinician ?? '',
    location: {
      pointOfCare: json.location?.pointOfCare ?? '',
      room: json.location?.room ?? '',
      bed: json.location?.bed ?? '',
      facility: json.location?.facility ?? '',
    },
  };
}
