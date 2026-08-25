package patient

import (
	"regexp"
	"strings"
)

// EscapeForPattern makes a user-supplied search term safe to embed in a query regular expression.
//
// The term reaches the database as part of a pattern, so an unescaped "(" is a malformed
// query and an unescaped "(a+)+$" is a denial-of-service against the database rather than
// against this process. Escaping is not optional just because the input is a name.
func EscapeForPattern(s string) string {
	return regexp.QuoteMeta(s)
}

// diacritics maps the accented Latin letters that occur in Spanish, Portuguese, French and
// German name data onto their unaccented forms.
//
// Written out rather than pulled from golang.org/x/text: the set that matters for patient
// names in this region is small and fixed, and an explicit table is auditable — the reader
// can see exactly which characters fold and satisfy themselves that ñ folds to n on purpose
// rather than as a side effect of a normalisation form.
var diacritics = map[rune]rune{
	'á': 'a', 'à': 'a', 'ä': 'a', 'â': 'a', 'ã': 'a', 'å': 'a',
	'é': 'e', 'è': 'e', 'ë': 'e', 'ê': 'e',
	'í': 'i', 'ì': 'i', 'ï': 'i', 'î': 'i',
	'ó': 'o', 'ò': 'o', 'ö': 'o', 'ô': 'o', 'õ': 'o',
	'ú': 'u', 'ù': 'u', 'ü': 'u', 'û': 'u',
	'ñ': 'n', 'ç': 'c', 'ý': 'y', 'ÿ': 'y',
}

// Fold normalises a string for comparison: lower-cased, diacritics removed, edges trimmed.
//
// Folding ñ to n is deliberate. In Spanish orthography ñ and n are distinct letters, so this
// is not a linguistically neutral operation — but patient names arrive from one system with
// accents and from another without, and a search that treats "Nunez" and "Núñez" as different
// people reports "no such patient" for someone who is currently admitted. Recall is the
// safer failure here; the caller still sees both records and decides.
func Fold(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range strings.ToLower(strings.TrimSpace(s)) {
		if folded, ok := diacritics[r]; ok {
			b.WriteRune(folded)
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}
