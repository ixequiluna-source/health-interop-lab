import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  type TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ENVIRONMENT, type AppEnvironment } from '../core/environment';
import { traceHeaderInterceptor } from '../core/trace-header.interceptor';
import { HttpPatientGateway } from './http-patient-gateway';
import { GatewayError, PatientGateway } from './patient-gateway';

const TEST_ENVIRONMENT: AppEnvironment = {
  label: 'spec',
  gateway: 'http',
  gatewayBaseUrl: 'https://proxy.test',
  pipelinePollMs: 30_000,
};

const SEARCH_URL = 'https://proxy.test/interop.v1.PatientService/SearchPatients';
const GET_URL = 'https://proxy.test/interop.v1.PatientService/GetPatient';
const ENCOUNTERS_URL = 'https://proxy.test/interop.v1.PatientService/ListEncounters';

describe('HttpPatientGateway', () => {
  let gateway: PatientGateway;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([traceHeaderInterceptor])),
        provideHttpClientTesting(),
        { provide: ENVIRONMENT, useValue: TEST_ENVIRONMENT },
        { provide: PatientGateway, useClass: HttpPatientGateway },
      ],
    });
    gateway = TestBed.inject(PatientGateway);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // Fails the test if any request was issued and not asserted on. Without this, a gateway
    // that fires a duplicate request per call passes every spec here.
    http.verify();
  });

  describe('the wire', () => {
    it('sends the search term in a POST body, never in the URL', () => {
      gateway.searchPatients({ query: 'García' }).subscribe();

      const request = http.expectOne(SEARCH_URL);

      // The PHI rule, asserted rather than documented. A name in a query string is written
      // into the proxy access log, the load balancer log, the browser's history and the
      // `Referer` of any outbound link — none of which sit inside the retention and access
      // regime the clinical store does.
      expect(request.request.method).toBe('POST');
      expect(request.request.urlWithParams).not.toContain('Garc');
      expect(request.request.urlWithParams).toBe(SEARCH_URL);
      expect(request.request.body).toEqual({ query: 'García' });

      request.flush({ patients: [], totalMatched: 0 });
    });

    it('forwards a page token verbatim and does not parse it', () => {
      // Tokens are the server's private encoding. The console must round-trip the exact
      // bytes, because the server is entitled to switch from an offset cursor to a keyset
      // without this client being redeployed.
      const opaque = 'this-is-not-a-format-the-client-knows.$$$';
      gateway.searchPatients({ query: 'garcia', pageToken: opaque }).subscribe();

      const request = http.expectOne(SEARCH_URL);
      expect(request.request.body).toEqual({ query: 'garcia', pageToken: opaque });

      request.flush({ patients: [], totalMatched: 0 });
    });

    it('omits pageSize and pageToken when unset rather than sending defaults', () => {
      gateway.searchPatients({ query: 'garcia' }).subscribe();

      const request = http.expectOne(SEARCH_URL);
      // Sending `pageToken: ""` would be a request for "the page after the empty cursor",
      // which the server is right to reject.
      expect(Object.keys(request.request.body as object)).toEqual(['query']);

      request.flush({ patients: [], totalMatched: 0 });
    });

    it('attaches a traceparent to every call', () => {
      gateway.getPatient('MRN-1').subscribe();

      const request = http.expectOne(GET_URL);
      expect(request.request.headers.get('traceparent')).toMatch(
        /^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/,
      );

      request.flush({ patient: { medicalRecordNumber: 'MRN-1' } });
    });
  });

  describe('proto3 JSON decoding', () => {
    it('fills in fields the encoding omits at their default value', () => {
      // proto3 JSON drops fields that hold the default, so a patient with no given name and
      // no identifiers arrives as `{"medicalRecordNumber":"MRN-1","familyName":"Luna"}`.
      // Letting those reach a template as `undefined` is how a strictly-typed component
      // renders "undefined" into a clinical field.
      let received: unknown;
      gateway.getPatient('MRN-1').subscribe((patient) => (received = patient));

      http.expectOne(GET_URL).flush({
        patient: { medicalRecordNumber: 'MRN-1', familyName: 'Luna' },
      });

      expect(received).toEqual({
        medicalRecordNumber: 'MRN-1',
        identifiers: [],
        familyName: 'Luna',
        givenName: '',
        birthDate: '',
        administrativeSex: '',
        lastUpdated: '',
      });
    });

    it('treats an omitted nextPageToken as end-of-results', () => {
      let token: string | null | undefined;
      gateway.searchPatients({ query: 'garcia' }).subscribe((page) => (token = page.nextPageToken));

      http.expectOne(SEARCH_URL).flush({ patients: [], totalMatched: 0 });

      // `null`, not `undefined` — the domain type says the field is always present and the
      // absence of a token is a positive statement about the result set.
      expect(token).toBeNull();
    });

    it('treats a well-formed response with no patient as not-found', () => {
      // `{}` is a legal `GetPatientResponse` in proto3 JSON. It is also a broken answer.
      const observed = vi.fn();
      gateway.getPatient('MRN-1').subscribe({ error: observed });

      http.expectOne(GET_URL).flush({});

      expect(observed).toHaveBeenCalledWith(expect.objectContaining({ kind: 'not-found' }));
    });

    it('decodes encounters with a partial location', () => {
      let received: readonly { location: { room: string } }[] = [];
      gateway.listEncounters('MRN-1').subscribe((encounters) => (received = encounters));

      http.expectOne(ENCOUNTERS_URL).flush({
        encounters: [{ visitNumber: 'VN-1', location: { pointOfCare: 'URG' } }],
      });

      expect(received[0]?.location.room).toBe('');
    });
  });

  describe('error mapping', () => {
    /** Issues a search, fails it as described, and returns the error the caller saw. */
    function failWith(apply: (request: TestRequest) => void): GatewayError {
      let error: unknown;
      gateway.searchPatients({ query: 'garcia' }).subscribe({ error: (e) => (error = e) });
      apply(http.expectOne(SEARCH_URL));

      if (!(error instanceof GatewayError)) {
        throw new Error(`expected a GatewayError, got ${String(error)}`);
      }
      return error;
    }

    it('maps a Connect not_found onto not-found', () => {
      const error = failWith((request) =>
        request.flush(
          { code: 'not_found', message: 'no such patient' },
          { status: 404, statusText: 'Not Found' },
        ),
      );
      expect(error.kind).toBe('not-found');
      expect(error.message).toBe('no such patient');
    });

    it('maps a Connect invalid_argument onto invalid-request', () => {
      // Reported as HTTP 400 by a Connect proxy — as are several other gRPC codes, which is
      // why the code in the body is consulted before the status.
      const error = failWith((request) =>
        request.flush(
          { code: 'invalid_argument', message: 'page token belongs to a different query' },
          { status: 400, statusText: 'Bad Request' },
        ),
      );
      expect(error.kind).toBe('invalid-request');
    });

    it('maps a Connect unavailable onto unavailable', () => {
      const error = failWith((request) =>
        request.flush(
          { code: 'unavailable', message: 'gateway draining' },
          { status: 503, statusText: 'Service Unavailable' },
        ),
      );
      expect(error.kind).toBe('unavailable');
    });

    it('falls back to the HTTP status when the body is not a Connect error', () => {
      // A proxy or load balancer failing in front of the service returns its own HTML error
      // page, not a Connect envelope.
      const error = failWith((request) =>
        request.flush('<html>502 Bad Gateway</html>', { status: 502, statusText: 'Bad Gateway' }),
      );
      expect(error.kind).toBe('unavailable');
    });

    it('reports a request that never reached the network as a network failure', () => {
      // `status: 0` in Angular means offline, DNS, TLS, or a refused CORS preflight — which
      // is where a missing `traceparent` in `Access-Control-Allow-Headers` surfaces.
      const error = failWith((request) =>
        request.error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' }),
      );
      expect(error.kind).toBe('network');
    });

    it('carries the trace id of the failed request', () => {
      // The payoff for the interceptor: the operator can read this off the error panel and
      // the backend team has the exact span tree.
      const error = failWith((request) =>
        request.flush(
          { code: 'internal', message: 'boom' },
          { status: 500, statusText: 'Internal Server Error' },
        ),
      );
      expect(error.traceId).toMatch(/^[0-9a-f]{32}$/);
    });

    it('keeps the trace id on an error raised while decoding the response', () => {
      // The not-found synthesised from an empty `GetPatientResponse` is minted before the
      // trace id is readable, so it has to be re-issued with the id attached.
      let error: unknown;
      gateway.getPatient('MRN-1').subscribe({ error: (e) => (error = e) });
      http.expectOne(GET_URL).flush({});

      expect((error as GatewayError).traceId).toMatch(/^[0-9a-f]{32}$/);
    });
  });

  it('reads pipeline status from the ops endpoint, not from PatientService', () => {
    // Health belongs to the deployment. Adding a `GetPipelineStatus` method to a patient-read
    // service would put an operational concern into a clinical API contract permanently.
    gateway.pipelineStatus().subscribe();

    const request = http.expectOne('https://proxy.test/ops/pipeline-status');
    expect(request.request.method).toBe('GET');

    request.flush({ services: [], observedAt: '2026-08-25T14:30:00Z' });
  });
});
