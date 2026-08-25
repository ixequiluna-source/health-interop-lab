import type { Type } from '@angular/core';

import type { AppEnvironment } from '../app/core/environment';
import { HttpPatientGateway } from '../app/data/http-patient-gateway';
import type { PatientGateway } from '../app/data/patient-gateway';

/**
 * Production configuration, substituted for `environment.ts` by the `fileReplacements` entry
 * in `angular.json`.
 *
 * `gatewayBaseUrl` is empty, making every request same-origin and relative — matched by the
 * `/interop.v1.` and `/ops/` locations in the container's nginx config, which proxy to the
 * Connect proxy. Same-origin is not a convenience: it removes the CORS preflight, and with it
 * the failure mode where a missing `traceparent` entry in `Access-Control-Allow-Headers`
 * makes every request fail as an unexplained network error.
 *
 * There is no secret and no per-tenant value here. Anything that varies per deployment has to
 * be supplied at container start (see the `Dockerfile`), not baked into a bundle that is then
 * wrong for every environment but one.
 *
 * Because this module replaces `environment.ts` at build time, `InMemoryPatientGateway` and
 * the sample data it imports are not reachable from the production import graph at all.
 */
export const environment: AppEnvironment = {
  label: 'production',
  gateway: 'http',
  gatewayBaseUrl: '',
  pipelinePollMs: 30_000,
};

export const gatewayImplementation: Type<PatientGateway> = HttpPatientGateway;
