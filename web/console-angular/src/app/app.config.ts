import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideBrowserGlobalErrorListeners, type ApplicationConfig } from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';

import { environment, gatewayImplementation } from '../environments/environment';
import { traceHeaderInterceptor } from './core/trace-header.interceptor';
import { provideGateway } from './data/gateway.providers';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),

    provideRouter(
      routes,
      // Route parameters arrive as component `input()`s, so `PatientDetailPage` never
      // injects `ActivatedRoute`. That keeps the component testable by setting an input
      // instead of by assembling a fake route snapshot, and it makes the MRN a typed,
      // reactive value rather than something read imperatively out of a param map.
      withComponentInputBinding(),
      // Restore scroll position on back-navigation, and start at the top otherwise.
      // Without this, returning from a patient record to a long, freshly re-fetched result
      // list drops the operator at the top of it.
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled', anchorScrolling: 'enabled' }),
    ),

    provideHttpClient(
      // `fetch` rather than `XMLHttpRequest`. The reason that matters here is
      // cancellation: `switchMap` unsubscribing from a superseded search must actually abort
      // the request, and the fetch backend does that through an `AbortController`. It is
      // also the only backend that will not be a special case if this console ever moves to
      // a server-rendered entry point.
      withFetch(),
      withInterceptors([traceHeaderInterceptor]),
    ),

    // The single point where the console decides whether it is talking to a real backend.
    // Both arguments come from the environment module, which `fileReplacements` swaps per
    // configuration — so the unused gateway implementation is not in the bundle at all.
    provideGateway(environment, gatewayImplementation),
  ],
};
