import { HttpContext, HttpRequest, type HttpEvent, type HttpHandlerFn } from '@angular/common/http';
import { EMPTY, type Observable } from 'rxjs';
import { describe, expect, it } from 'vitest';

import { newTraceparent, TRACE_ID, traceIdOf } from './trace-context';
import { traceHeaderInterceptor } from './trace-header.interceptor';

/**
 * Captures the request the interceptor forwards.
 *
 * The interceptor is a plain function, so it is called directly — no `TestBed`, no HTTP
 * backend, no module. That is the practical argument for `HttpInterceptorFn` over a class:
 * the unit under test is a function of two arguments and the test looks like it.
 */
function capture(): { next: HttpHandlerFn; forwarded: () => HttpRequest<unknown> } {
  let seen: HttpRequest<unknown> | null = null;
  const next: HttpHandlerFn = (request): Observable<HttpEvent<unknown>> => {
    seen = request;
    return EMPTY;
  };
  return {
    next,
    forwarded: () => {
      if (seen === null) {
        throw new Error('the interceptor did not forward the request');
      }
      return seen;
    },
  };
}

function get(context = new HttpContext()): HttpRequest<unknown> {
  return new HttpRequest('GET', '/interop.v1.PatientService/GetPatient', { context });
}

describe('traceHeaderInterceptor', () => {
  it('attaches a traceparent to a request that has none', () => {
    const { next, forwarded } = capture();
    traceHeaderInterceptor(get(), next).subscribe();

    expect(forwarded().headers.get('traceparent')).toBeTruthy();
  });

  it('emits a header in W3C Trace Context format', () => {
    const { next, forwarded } = capture();
    traceHeaderInterceptor(get(), next).subscribe();

    // Version `00`, a 32-hex trace id, a 16-hex span id, and the sampled flag. A receiver is
    // entitled to reject anything else outright, so the shape is asserted exactly rather
    // than loosely — a header the collector silently discards is worse than no header,
    // because it looks like it is working.
    expect(forwarded().headers.get('traceparent')).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
  });

  it('never emits an all-zero trace or span id', () => {
    // Both are forbidden by the spec, and a conformant collector drops the whole trace.
    for (let i = 0; i < 200; i++) {
      const { next, forwarded } = capture();
      traceHeaderInterceptor(get(), next).subscribe();
      const header = forwarded().headers.get('traceparent') ?? '';
      const [, traceId, spanId] = header.split('-');
      expect(traceId).not.toMatch(/^0+$/);
      expect(spanId).not.toMatch(/^0+$/);
    }
  });

  it('generates a distinct trace id per request', () => {
    // One trace id reused across a session would produce a single trace containing every
    // call the operator made all day, which trace backends truncate and mis-render.
    const ids = new Set<string>();
    for (let i = 0; i < 50; i++) {
      const { next, forwarded } = capture();
      traceHeaderInterceptor(get(), next).subscribe();
      ids.add(forwarded().headers.get('traceparent') ?? '');
    }
    expect(ids.size).toBe(50);
  });

  it('publishes the trace id on the request context so the caller can read it back', () => {
    // The mechanism that puts a trace id on the error panel. `HttpErrorResponse` carries no
    // reference to its request, so without this channel a failed call has no id to show.
    const context = new HttpContext();
    const { next, forwarded } = capture();

    traceHeaderInterceptor(get(context), next).subscribe();

    const published = context.get(TRACE_ID);
    expect(published).toMatch(/^[0-9a-f]{32}$/);
    expect(traceIdOf(forwarded().headers.get('traceparent') ?? '')).toBe(published);
  });

  it('leaves an existing traceparent alone', () => {
    // A caller that already set one is continuing a trace it knows about; overwriting would
    // sever exactly the link it was trying to make.
    const existing = newTraceparent('0af7651916cd43dd8448eb211c80319c', 'b7ad6b7169203331');
    const context = new HttpContext();
    const { next, forwarded } = capture();

    traceHeaderInterceptor(
      get(context).clone({ setHeaders: { traceparent: existing } }),
      next,
    ).subscribe();

    expect(forwarded().headers.get('traceparent')).toBe(existing);
    expect(context.get(TRACE_ID)).toBeNull();
  });
});
