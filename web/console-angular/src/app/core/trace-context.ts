import { HttpContextToken } from '@angular/common/http';

/**
 * W3C Trace Context generation for outbound calls.
 *
 * The backend is instrumented with OpenTelemetry end to end — the Go gateway installs an
 * `otelgrpc` stats handler, the Kotlin mapper and the Java ingest export OTLP. What that
 * instrumentation cannot do on its own is start the trace at the click. A span tree that
 * begins at the Connect proxy answers "which of these gateway calls was slow"; it does not
 * answer "the operator says search hung at 14:32, which call was that", because there is
 * nothing tying a browser action to a trace id.
 *
 * Minting a `traceparent` here closes that gap for the cost of one header. It is
 * deliberately the *only* thing the console does about tracing: no OTel browser SDK, no
 * exporter, no batch processor. Full browser instrumentation is a real amount of bundle and
 * a real amount of config for a console whose own timings are not the interesting part —
 * what is interesting is the backend work, and a correlatable id is enough to reach it.
 *
 * @see https://www.w3.org/TR/trace-context/
 */

/** The only version of the `traceparent` format currently defined. */
const VERSION = '00';

/**
 * Trace flags: `01` is the sampled bit.
 *
 * Every request from this console is marked sampled. That is a considered choice, not a
 * default: the backend samples at 10% (`OTEL_TRACES_SAMPLER_ARG=0.1`), which is right for
 * machine-generated traffic and wrong for the handful of calls a human made while watching
 * the screen. Those are the ones someone will come looking for, and a 90% chance the trace
 * was dropped makes the header useless for the case it exists to serve. The volume is
 * negligible — a console operator generates single-digit requests per second at worst.
 */
const FLAGS_SAMPLED = '01';

/**
 * The generated `traceparent` for a request, readable by whoever issued it.
 *
 * `HttpContext` is the mechanism because it is the only channel that runs *backwards* from
 * an interceptor to the caller. Headers go outbound; the response can be rewritten; but a
 * failing request produces an `HttpErrorResponse`, which carries no reference to the request
 * that caused it. A caller that wants to show the operator the trace id of a call that
 * failed would otherwise have to generate the id itself and pass it down — which puts
 * trace-format knowledge in every call site.
 *
 * `HttpRequest.clone()` preserves the `HttpContext` instance by reference, so the object the
 * caller constructed is the same object the interceptor writes into. That is documented
 * behaviour, and it is what makes this a supported pattern rather than a trick.
 */
export const TRACE_ID = new HttpContextToken<string | null>(() => null);

/** Fills `bytes` with cryptographically random values, or `Math.random` where unavailable. */
function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  // `globalThis.crypto` is present in every browser this console targets and in jsdom under
  // Node 20+, but the guard stays: the fallback path is three lines and the alternative is a
  // hard crash in an environment nobody anticipated. These ids are correlation handles, not
  // secrets — an attacker who can guess one learns which trace id to ask a server they do
  // not have access to about — so a degraded source of randomness is a real but small loss.
  if (typeof globalThis.crypto?.getRandomValues === 'function') {
    globalThis.crypto.getRandomValues(bytes);
    return bytes;
  }
  for (let i = 0; i < length; i++) {
    bytes[i] = Math.floor(Math.random() * 256);
  }
  return bytes;
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

/**
 * A 16-byte trace id as 32 lowercase hex characters.
 *
 * The spec forbids an all-zero id, and a receiver is required to reject one. The retry loop
 * is not defensive noise: with a working CSPRNG it is unreachable, and with a broken one it
 * is the difference between "traces are missing" and "every request is rejected by the
 * collector for an invalid header".
 */
export function newTraceId(): string {
  for (let attempt = 0; attempt < 4; attempt++) {
    const hex = toHex(randomBytes(16));
    if (!/^0{32}$/.test(hex)) {
      return hex;
    }
  }
  // Deterministic, obviously-synthetic, and valid. Better than emitting a header a
  // conformant collector will discard.
  return '00000000000000000000000000000001';
}

/** An 8-byte span id as 16 lowercase hex characters. Also may not be all zeroes. */
export function newSpanId(): string {
  for (let attempt = 0; attempt < 4; attempt++) {
    const hex = toHex(randomBytes(8));
    if (!/^0{16}$/.test(hex)) {
      return hex;
    }
  }
  return '0000000000000001';
}

/**
 * Builds a `traceparent` header value.
 *
 * The console is always the *root* of the trace: it has no inbound `traceparent` to continue
 * from, so it mints a fresh trace id and an initial span id per request. Reusing one trace
 * id across the console's whole session would be wrong in a way that is worth naming — it
 * would produce a single trace containing every call an operator made all day, and trace
 * backends both truncate and mis-render those.
 */
export function newTraceparent(
  traceId: string = newTraceId(),
  spanId: string = newSpanId(),
): string {
  return `${VERSION}-${traceId}-${spanId}-${FLAGS_SAMPLED}`;
}

/** Extracts the trace id from a `traceparent`, or `null` if it is not well formed. */
export function traceIdOf(traceparent: string): string | null {
  const match = /^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$/.exec(traceparent);
  return match?.[1] ?? null;
}
