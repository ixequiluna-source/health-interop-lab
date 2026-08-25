import type { HttpInterceptorFn } from '@angular/common/http';

import { newTraceId, newTraceparent, TRACE_ID } from './trace-context';

/**
 * Attaches a W3C `traceparent` to every outbound request and records the trace id where the
 * caller can read it back.
 *
 * ## Why an interceptor rather than a header on each call
 *
 * Correlation is only worth anything if it is total. A header set per call site is a header
 * that is missing from the call site added next quarter, and the gap shows up as the one
 * request nobody can find a trace for — usually the one being investigated. An interceptor
 * is the only place in Angular's HTTP stack where "every request" is enforceable.
 *
 * ## What this buys, concretely
 *
 * The Go gateway runs `otelgrpc.NewServerHandler()`, and the Connect proxy in front of it
 * propagates `traceparent` into the gRPC metadata. With this header set, one search in this
 * console produces a trace that spans console → proxy → gateway → Mongo. Without it, the
 * trace starts at the proxy and there is no way to say which of the day's gateway calls was
 * the one the operator was looking at. `GatewayError.traceId` puts that id on screen, so a
 * bug report is a trace id rather than a timestamp and a description.
 *
 * ## Why it is a functional interceptor
 *
 * `HttpInterceptorFn` is tree-shakable and, more usefully here, trivially testable: it is a
 * plain function of `(req, next)` and the specs call it directly without a `TestBed` module
 * or a class instance. A class-based `HttpInterceptor` would need a DI ceremony to assert
 * the same one line of behaviour.
 *
 * ## Why an existing `traceparent` is not overwritten
 *
 * A caller that already set one is continuing a trace it knows about, and clobbering it
 * would sever the link it was trying to make. This interceptor establishes a floor, not a
 * policy.
 *
 * ## Deployment note
 *
 * `traceparent` is not a CORS-safelisted request header. When the console is served from a
 * different origin than the Connect proxy, the proxy must list it in
 * `Access-Control-Allow-Headers` or every request fails preflight — and it fails as an
 * opaque network error, which reads like the backend being down rather than like a missing
 * CORS entry. Serving the console and the proxy from one origin (what the `Dockerfile`'s
 * nginx does, via a `/interop.v1.*` location) sidesteps the preflight entirely and is the
 * configuration this is built for.
 */
export const traceHeaderInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.headers.has('traceparent')) {
    return next(req);
  }

  const traceId = newTraceId();

  // Written into the request's `HttpContext`, which `clone()` carries by reference, so the
  // caller that constructed the context can read the id back — including from a `catchError`
  // handling an `HttpErrorResponse`, which carries no reference to its request. This is the
  // only path by which an id minted here reaches the code that has to display it.
  req.context.set(TRACE_ID, traceId);

  return next(
    req.clone({
      setHeaders: { traceparent: newTraceparent(traceId) },
    }),
  );
};
