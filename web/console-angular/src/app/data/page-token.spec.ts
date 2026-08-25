import { describe, expect, it } from 'vitest';

import { decodePageToken, encodePageToken, hashTerm, InvalidPageTokenError } from './page-token';

describe('page tokens', () => {
  it('round-trips an offset', () => {
    const cursor = decodePageToken(encodePageToken('garcía', 25));
    expect(cursor.offset).toBe(25);
  });

  it('is opaque: the search term does not appear in the token', () => {
    // The point of hashing the term. A token is a query-string- and log-safe string, and a
    // patient name that survived base64 into a proxy access log is a disclosure through a
    // channel outside the audited boundary.
    const token = encodePageToken('Hernández', 50);
    expect(token).not.toContain('Hern');
    expect(atob(token.replace(/-/g, '+').replace(/_/g, '/'))).not.toContain('Hern');
  });

  it('binds a token to the query that produced it', () => {
    const forGarcia = decodePageToken(encodePageToken('garcia', 25));
    expect(forGarcia.termHash).toBe(hashTerm('garcia'));
    expect(forGarcia.termHash).not.toBe(hashTerm('hernandez'));
  });

  it('folds the term before hashing, so accents do not split a result set', () => {
    // A cursor minted while searching "Núñez" has to keep working if the same search is
    // re-issued as "Nunez": the two match the same patients, so they must page as one set.
    expect(hashTerm('Núñez')).toBe(hashTerm('nunez'));
    expect(hashTerm('  GARCÍA  ')).toBe(hashTerm('garcia'));
  });

  it('rejects a token that is not base64', () => {
    expect(() => decodePageToken('not a token!!')).toThrow(InvalidPageTokenError);
  });

  it('rejects a token from an unrecognised format version', () => {
    // Guards the upgrade path: a token minted by an older build must not be reinterpreted by
    // a newer format as a plausible offset into the wrong result set.
    const v2 = btoa('v2:25:deadbeef').replace(/=+$/, '');
    expect(() => decodePageToken(v2)).toThrow(InvalidPageTokenError);
  });

  it('rejects a non-integer or negative offset', () => {
    // `Number('25abc')` is NaN where `parseInt` would return 25; this asserts the strict
    // form is the one in use, because the lenient one turns corruption into a valid page.
    expect(() => decodePageToken(btoa('v1:25abc:deadbeef').replace(/=+$/, ''))).toThrow(
      InvalidPageTokenError,
    );
    expect(() => decodePageToken(btoa('v1:-1:deadbeef').replace(/=+$/, ''))).toThrow(
      InvalidPageTokenError,
    );
  });

  it('rejects a token with no query fingerprint', () => {
    expect(() => decodePageToken(btoa('v1:25:').replace(/=+$/, ''))).toThrow(InvalidPageTokenError);
  });

  it('reports every malformed token identically', () => {
    // No oracle: a caller cannot distinguish "bad base64" from "wrong version" from "bad
    // offset", because there is nothing different it could do about any of them.
    const messages = ['zzz!', btoa('v9:1:aa'), btoa('v1:x:aa'), btoa('v1:1:')].map((token) => {
      try {
        decodePageToken(token);
        return 'no error';
      } catch (error) {
        return (error as Error).message;
      }
    });
    expect(new Set(messages).size).toBe(1);
  });

  it('produces URL-safe output with no padding', () => {
    // Tokens travel in JSON bodies today, but a token that needs escaping is a token that
    // will eventually be mangled by something in the path.
    for (let offset = 0; offset < 200; offset += 7) {
      expect(encodePageToken(`term-${offset}`, offset)).toMatch(/^[A-Za-z0-9_-]+$/);
    }
  });
});
