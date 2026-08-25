import type { Type } from '@angular/core';

import type { AppEnvironment } from '../app/core/environment';
import { InMemoryPatientGateway } from '../app/data/in-memory-patient-gateway';
import type { PatientGateway } from '../app/data/patient-gateway';

/**
 * Development configuration.
 *
 * Defaults to the in-memory gateway so `ng serve` works with nothing else running. That is
 * the only reason it is the default — the in-memory implementation honours the full
 * pagination contract, so developing against it does not teach the UI assumptions the real
 * gateway will violate.
 *
 * To develop against a live proxy: set `gateway` to `'http'`, point `gatewayBaseUrl` at it,
 * and change `gatewayImplementation` to `HttpPatientGateway`. Both are in this file so the
 * two cannot disagree without it being visible in a single screen of code.
 */
export const environment: AppEnvironment = {
  label: 'local · in-memory',
  gateway: 'in-memory',
  gatewayBaseUrl: '/api',
  pipelinePollMs: 15_000,
};

/**
 * The class bound to `PatientGateway` in this build.
 *
 * Exported from the environment module rather than chosen by a `switch`, because
 * `fileReplacements` swaps this module wholesale — so the implementation this build does not
 * use is absent from its import graph and therefore absent from its bundle. See
 * `provideGateway` for the full reasoning.
 */
export const gatewayImplementation: Type<PatientGateway> = InMemoryPatientGateway;
