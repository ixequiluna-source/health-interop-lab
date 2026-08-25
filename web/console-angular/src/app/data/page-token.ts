import { fold } from '../domain/patient';

/**
 * The page-token format minted by `InMemoryPatientGateway`.
 *
 * ## This is not shared with the server, and that is the point
 *
 * `HttpPatientGateway` never imports this module. Page tokens from the real gateway are
 * *opaque*: the console receives a string and hands the same string back, and it must keep
 * working if the server switches from an offset cursor to a Mongo `_id` range or a keyset on
 * (familyName, mrn) without the console being redeployed. The moment a client parses a token
 * it has taken a dependency on a server implementation detail that the server believes it is
 * free to change — and the failure lands as wrong rows on screen, not as a build error.
 *
 * So this module exists only so the in-memory implementation can honour the *same contract*
 * the server does. It mirrors the Go `patient.EncodeCursor` layout (`v1:offset:termHash`,
 * base64url) because a developer stepping between the two should see a familiar shape, not
 * because anything requires the two to agree.
 *
 * ## Why the term is hashed rather than embedded
 *
 * The search term is a patient name. Tokens travel wherever the request travels, and a
 * base64 string in a log line is not meaningfully redacted — it is one `base64 -d` from
 * being a name in a proxy access log that is outside the audited retention boundary. The
 * hash gives the gateway what it actually needs (does this token belong to this query?)
 * without carrying the name.
 */

/** Prefix on every token so an old build's token cannot be misread by a new format. */
const CURSOR_VERSION = 'v1';

/** The decoded form of a page token. */
export interface Cursor {
  readonly offset: number;
  readonly termHash: string;
}

export class InvalidPageTokenError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'InvalidPageTokenError';
  }
}

/**
 * FNV-1a, 32-bit, over the folded term.
 *
 * Non-cryptographic on purpose. The Go gateway truncates a SHA-256 because its tokens cross
 * a trust boundary and a caller who can forge a term hash can pair an arbitrary offset with
 * an arbitrary query. Nothing crosses a boundary here: this gateway and its "server" are the
 * same object in the same tab. What is still needed is *collision resistance good enough to
 * catch a mistake* — the operator retyping their search while a "load more" is in flight —
 * and 32 bits of FNV is comfortably that.
 *
 * The alternative, `crypto.subtle.digest`, is async and would turn a synchronous token mint
 * into a promise for no benefit the threat model asks for.
 */
export function hashTerm(term: string): string {
  const folded = fold(term);
  let hash = 0x811c9dc5;
  for (let i = 0; i < folded.length; i++) {
    hash ^= folded.charCodeAt(i);
    // `Math.imul` is the only way to get a real 32-bit multiply in JS: `hash * 16777619`
    // silently exceeds 2^53 and loses the low bits that carry the entropy.
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
}

function toBase64Url(value: string): string {
  return btoa(value).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(value: string): string {
  // `atob` rejects the URL alphabet and, in some engines, unpadded input. Restore both
  // before decoding rather than trusting the runtime to be lenient.
  const padded = value.replace(/-/g, '+').replace(/_/g, '/');
  return atob(padded.padEnd(padded.length + ((4 - (padded.length % 4)) % 4), '='));
}

/** Mints the token for the next slice of a result set. */
export function encodePageToken(term: string, offset: number): string {
  return toBase64Url(`${CURSOR_VERSION}:${offset}:${hashTerm(term)}`);
}

/**
 * Parses a page token.
 *
 * Every failure raises the same error with the same message. Distinguishing "not base64"
 * from "bad version" from "negative offset" turns the endpoint into an oracle for probing
 * the token format, and no caller can act on the difference anyway — the only correct
 * client response to any of them is to restart the search from page one.
 */
export function decodePageToken(token: string): Cursor {
  const invalid = (): never => {
    throw new InvalidPageTokenError('page token is not valid');
  };

  let decoded: string;
  try {
    decoded = fromBase64Url(token);
  } catch {
    return invalid();
  }

  const parts = decoded.split(':');
  if (parts.length !== 3 || parts[0] !== CURSOR_VERSION) {
    return invalid();
  }

  const [, rawOffset, termHash] = parts;
  // `Number('12abc')` is NaN but `parseInt('12abc')` is 12; the strict form is required or a
  // corrupt token silently becomes a plausible offset.
  const offset = Number(rawOffset);
  if (!Number.isInteger(offset) || offset < 0) {
    return invalid();
  }
  if (!termHash) {
    return invalid();
  }

  return { offset, termHash };
}
