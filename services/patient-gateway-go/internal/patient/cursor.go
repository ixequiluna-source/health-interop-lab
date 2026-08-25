package patient

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"
)

// cursorVersion prefixes every token so the format can change without a token from an old
// build being silently misread as an offset in the new one.
const cursorVersion = "v1"

// Cursor is the decoded form of a page token.
//
// It carries a hash of the search term rather than the term itself. Page tokens travel in
// query strings, proxy logs and browser history; a patient name embedded in one is a
// disclosure of PHI through a channel that is rarely inside the audited boundary.
type Cursor struct {
	Offset   int
	TermHash string
}

// HashTerm returns the stable, non-reversible identity of a search term.
func HashTerm(term string) string {
	sum := sha256.Sum256([]byte(Fold(term)))
	return hex.EncodeToString(sum[:8])
}

// EncodeCursor renders a page token for the next slice of a result set.
func EncodeCursor(term string, offset int) string {
	raw := fmt.Sprintf("%s:%d:%s", cursorVersion, offset, HashTerm(term))
	return base64.RawURLEncoding.EncodeToString([]byte(raw))
}

// DecodeCursor parses a page token.
//
// Every failure returns ErrInvalidCursor rather than a decoding-specific error: a client
// cannot act on the difference, and distinguishing them turns the endpoint into an oracle
// for probing the token format.
func DecodeCursor(token string) (Cursor, error) {
	decoded, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		return Cursor{}, fmt.Errorf("%w: not base64", ErrInvalidCursor)
	}
	parts := strings.Split(string(decoded), ":")
	if len(parts) != 3 || parts[0] != cursorVersion {
		return Cursor{}, fmt.Errorf("%w: unrecognised token format", ErrInvalidCursor)
	}
	offset, err := strconv.Atoi(parts[1])
	if err != nil || offset < 0 {
		return Cursor{}, fmt.Errorf("%w: offset is not a non-negative integer", ErrInvalidCursor)
	}
	if parts[2] == "" {
		return Cursor{}, fmt.Errorf("%w: missing query fingerprint", ErrInvalidCursor)
	}
	return Cursor{Offset: offset, TermHash: parts[2]}, nil
}
