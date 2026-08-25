import { InjectionToken } from '@angular/core';

/**
 * Which `PatientGateway` implementation to wire in.
 *
 * A string union rather than a boolean. `useMockData: true` reads fine until someone adds a
 * third source — a recorded-fixture gateway, a second cluster — and the flag becomes
 * `useMockData && !useRecorded`. A closed union forces the `switch` in `provideGateway` to
 * be exhaustive, and `noFallthroughCasesInSwitch` plus the `never` default make adding a
 * member a compile error at every site that has to handle it.
 */
export type GatewayKind = 'http' | 'in-memory';

export interface AppEnvironment {
  /** Shown in the header so a screenshot is self-identifying. */
  readonly label: string;

  readonly gateway: GatewayKind;

  /**
   * Origin of the Connect proxy, ignored when `gateway` is `in-memory`.
   *
   * A same-origin relative path by default. Pointing it at another origin works, but costs a
   * CORS preflight on every request — and the `traceparent` header this console sets is not
   * CORS-safelisted, so the proxy must explicitly allow it or every call fails as an opaque
   * network error.
   */
  readonly gatewayBaseUrl: string;

  /**
   * How long the pipeline dashboard waits between polls, in milliseconds.
   *
   * Configurable because the right number depends on what is behind the endpoint. Against
   * the aggregating edge it is cheap; against a deployment that fans out to five `/healthz`
   * probes per poll, a console left open on a wall display all week is a meaningful load
   * that nobody attributes to the dashboard.
   */
  readonly pipelinePollMs: number;
}

/**
 * The environment, as a DI token rather than a module-level import.
 *
 * The usual `import { environment } from '../environments/environment'` works and is what the
 * CLI scaffolds, but it makes every consumer statically bound to a build-time constant —
 * which means a spec that wants to exercise the HTTP gateway against a different base URL
 * has to reach for module mocking. A token is overridable in a `TestBed` in one line, and it
 * makes the dependency visible in the constructor rather than hidden in the import list.
 */
export const ENVIRONMENT = new InjectionToken<AppEnvironment>('ENVIRONMENT');
