import { makeEnvironmentProviders, type EnvironmentProviders, type Type } from '@angular/core';

import { ENVIRONMENT, type AppEnvironment } from '../core/environment';
import { PatientGateway } from './patient-gateway';

/**
 * Binds `PatientGateway` and publishes the environment.
 *
 * ## Why the implementation is a parameter rather than a `switch` on `environment.gateway`
 *
 * The obvious version of this function reads the flag and picks a class:
 *
 * ```ts
 * switch (environment.gateway) {
 *   case 'http':      return { provide: PatientGateway, useClass: HttpPatientGateway };
 *   case 'in-memory': return { provide: PatientGateway, useClass: InMemoryPatientGateway };
 * }
 * ```
 *
 * It works, and it ships both implementations to every user. The branch is decided at
 * runtime, so the bundler must retain both classes — and `InMemoryPatientGateway` drags in
 * `sample-data.ts`, forty-eight synthetic patient records and a pipeline snapshot, into the
 * production bundle. Nothing there is real data, so it is not a disclosure; it is dead weight
 * that grows every time the fixture is made more realistic, and it is a second, live code
 * path in a production artefact that nobody tests there.
 *
 * Instead each environment module names its own implementation, and `fileReplacements` in
 * `angular.json` swaps the whole module at build time. The production build's import graph
 * then physically does not contain `InMemoryPatientGateway`, so it is not in the output —
 * this is static elimination, not a minifier heuristic that might change.
 *
 * `environment.gateway` survives as a descriptive flag: it drives the label in the masthead
 * and it documents, in the same twenty-line file as the class export, which implementation
 * that file binds. Keeping the two adjacent is what keeps them from drifting.
 *
 * ## Why `makeEnvironmentProviders`
 *
 * So the result cannot be dropped into a component's `providers`. A component that
 * re-provided the gateway would get a second instance with its own in-flight requests, and
 * the resulting duplicate-fetch bug is a genuinely unpleasant one to find.
 */
export function provideGateway(
  environment: AppEnvironment,
  implementation: Type<PatientGateway>,
): EnvironmentProviders {
  return makeEnvironmentProviders([
    { provide: ENVIRONMENT, useValue: environment },
    { provide: PatientGateway, useClass: implementation },
  ]);
}
