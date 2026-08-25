package patient

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"testing"
	"time"
)

func seed(t *testing.T, n int) *MemoryStore {
	t.Helper()
	s := NewMemoryStore()
	for i := 0; i < n; i++ {
		s.Put(Patient{
			MedicalRecordNumber: fmt.Sprintf("MRN-%03d", i),
			FamilyName:          "Luna",
			GivenName:           fmt.Sprintf("Paciente%03d", i),
			BirthDate:           "1990-03-14",
			AdministrativeSex:   "M",
			LastUpdated:         time.Unix(1700000000, 0).UTC(),
		})
	}
	return s
}

func TestFold(t *testing.T) {
	cases := map[string]string{
		"Núñez":     "nunez",
		"NÚÑEZ":     "nunez",
		"  José  ":  "jose",
		"Müller":    "muller",
		"Gonçalves": "goncalves",
		"Luna":      "luna",
		"":          "",
	}
	for in, want := range cases {
		if got := Fold(in); got != want {
			t.Errorf("Fold(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestMatchesIsDiacriticInsensitive(t *testing.T) {
	p := Patient{
		MedicalRecordNumber: "MRN-1",
		FamilyName:          "Núñez",
		GivenName:           "José",
		Identifiers:         []Identifier{{System: "IMSS", Value: "NSS-99", Type: "SS"}},
	}
	for _, term := range []string{"nunez", "NUNEZ", "Núñez", "jose", "José", "MRN-1", "NSS-99"} {
		if !p.Matches(term) {
			t.Errorf("expected %q to match a patient named Núñez José", term)
		}
	}
	for _, term := range []string{"garcia", "MRN-2", ""} {
		if p.Matches(term) {
			t.Errorf("did not expect %q to match", term)
		}
	}
}

func TestNewSearchQueryDefaultsAndClamps(t *testing.T) {
	q, err := NewSearchQuery("luna", 0, "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if q.PageSize != DefaultPageSize {
		t.Errorf("page size = %d, want the default %d", q.PageSize, DefaultPageSize)
	}

	q, err = NewSearchQuery("luna", 5000, "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if q.PageSize != MaxPageSize {
		t.Errorf("page size = %d, want it clamped to %d", q.PageSize, MaxPageSize)
	}
}

func TestNewSearchQueryRejectsBadInput(t *testing.T) {
	if _, err := NewSearchQuery("a", 10, ""); !errors.Is(err, ErrInvalidRequest) {
		t.Errorf("a one-character query should be rejected, got %v", err)
	}
	if _, err := NewSearchQuery("   ", 10, ""); !errors.Is(err, ErrInvalidRequest) {
		t.Errorf("a whitespace query should be rejected, got %v", err)
	}
	if _, err := NewSearchQuery("luna", -1, ""); !errors.Is(err, ErrInvalidRequest) {
		t.Errorf("a negative page size should be rejected, got %v", err)
	}
}

func TestCursorRoundTrip(t *testing.T) {
	token := EncodeCursor("luna", 50)
	c, err := DecodeCursor(token)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if c.Offset != 50 {
		t.Errorf("offset = %d, want 50", c.Offset)
	}
	if c.TermHash != HashTerm("luna") {
		t.Errorf("term hash did not survive the round trip")
	}
}

func TestCursorDoesNotLeakTheSearchTerm(t *testing.T) {
	token := EncodeCursor("Núñez", 10)
	if strings.Contains(strings.ToLower(token), "nunez") {
		t.Errorf("page token %q leaks the search term", token)
	}
}

func TestCursorFromAnotherQueryIsRejected(t *testing.T) {
	token := EncodeCursor("luna", 25)
	_, err := NewSearchQuery("garcia", 25, token)
	if !errors.Is(err, ErrInvalidCursor) {
		t.Fatalf("a cursor from a different query must be rejected, got %v", err)
	}
}

func TestDecodeCursorRejectsMalformedTokens(t *testing.T) {
	for _, token := range []string{"!!!not base64!!!", "YWJj", "djE6bm90YW51bWJlcjphYmM", "djE6LTE6YWJj"} {
		if _, err := DecodeCursor(token); !errors.Is(err, ErrInvalidCursor) {
			t.Errorf("DecodeCursor(%q) should fail with ErrInvalidCursor, got %v", token, err)
		}
	}
}

func TestGetReturnsNotFound(t *testing.T) {
	s := seed(t, 3)
	if _, err := s.Get(context.Background(), "MRN-999"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
	if _, err := s.Get(context.Background(), " "); !errors.Is(err, ErrInvalidRequest) {
		t.Fatalf("expected ErrInvalidRequest for a blank identifier, got %v", err)
	}
}

func TestSearchPaginatesWithoutRepeatingOrSkipping(t *testing.T) {
	const total = 57
	const pageSize = 10
	s := seed(t, total)

	seen := map[string]bool{}
	token := ""
	pages := 0
	for {
		q, err := NewSearchQuery("luna", pageSize, token)
		if err != nil {
			t.Fatalf("page %d: %v", pages, err)
		}
		page, err := s.Search(context.Background(), q)
		if err != nil {
			t.Fatalf("page %d: %v", pages, err)
		}
		if page.TotalMatched != total {
			t.Fatalf("total matched = %d, want %d", page.TotalMatched, total)
		}
		for _, p := range page.Patients {
			if seen[p.MedicalRecordNumber] {
				t.Fatalf("%s appeared on two pages", p.MedicalRecordNumber)
			}
			seen[p.MedicalRecordNumber] = true
		}
		pages++
		if page.NextPageToken == "" {
			break
		}
		token = page.NextPageToken
		if pages > 20 {
			t.Fatal("pagination did not terminate")
		}
	}

	if len(seen) != total {
		t.Fatalf("walked %d patients, want %d", len(seen), total)
	}
	if want := (total + pageSize - 1) / pageSize; pages != want {
		t.Fatalf("walked %d pages, want %d", pages, want)
	}
}

func TestSearchOrderingIsStableAcrossRuns(t *testing.T) {
	s := seed(t, 30)
	q, err := NewSearchQuery("luna", 30, "")
	if err != nil {
		t.Fatal(err)
	}

	first, err := s.Search(context.Background(), q)
	if err != nil {
		t.Fatal(err)
	}
	// Map iteration order is randomised per run; repeat enough to catch an unsorted result.
	for i := 0; i < 25; i++ {
		again, err := s.Search(context.Background(), q)
		if err != nil {
			t.Fatal(err)
		}
		for j := range again.Patients {
			if again.Patients[j].MedicalRecordNumber != first.Patients[j].MedicalRecordNumber {
				t.Fatalf("ordering is not deterministic at position %d on iteration %d", j, i)
			}
		}
	}
}

func TestSearchOffsetPastTheEndReturnsEmpty(t *testing.T) {
	s := seed(t, 5)
	q, err := NewSearchQuery("luna", 10, EncodeCursor("luna", 500))
	if err != nil {
		t.Fatal(err)
	}
	page, err := s.Search(context.Background(), q)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Patients) != 0 || page.NextPageToken != "" {
		t.Fatalf("expected an empty final page, got %d patients and token %q",
			len(page.Patients), page.NextPageToken)
	}
	if page.TotalMatched != 5 {
		t.Fatalf("total matched = %d, want 5", page.TotalMatched)
	}
}

func TestSearchCapPreventsBulkExtraction(t *testing.T) {
	s := seed(t, 500)
	q, err := NewSearchQuery("luna", 100000, "")
	if err != nil {
		t.Fatal(err)
	}
	page, err := s.Search(context.Background(), q)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Patients) > MaxPageSize {
		t.Fatalf("returned %d patients in one page; the cap is %d", len(page.Patients), MaxPageSize)
	}
}

func TestEncountersAreNewestFirst(t *testing.T) {
	s := seed(t, 1)
	base := time.Date(2026, 8, 25, 10, 0, 0, 0, time.UTC)
	for i, offset := range []time.Duration{0, 48 * time.Hour, 24 * time.Hour} {
		s.AddEncounter(Encounter{
			VisitNumber:         fmt.Sprintf("VN-%d", i),
			MedicalRecordNumber: "MRN-000",
			AdmittedAt:          base.Add(offset),
		})
	}

	got, err := s.Encounters(context.Background(), "MRN-000")
	if err != nil {
		t.Fatal(err)
	}
	for i := 1; i < len(got); i++ {
		if got[i-1].AdmittedAt.Before(got[i].AdmittedAt) {
			t.Fatalf("encounters are not newest-first at position %d", i)
		}
	}
}

func TestEncountersForUnknownPatient(t *testing.T) {
	s := seed(t, 1)
	if _, err := s.Encounters(context.Background(), "MRN-999"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
}

func TestCancelledContextIsHonoured(t *testing.T) {
	s := seed(t, 3)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	if _, err := s.Get(ctx, "MRN-000"); !errors.Is(err, context.Canceled) {
		t.Errorf("Get should honour a cancelled context, got %v", err)
	}
	q, _ := NewSearchQuery("luna", 10, "")
	if _, err := s.Search(ctx, q); !errors.Is(err, context.Canceled) {
		t.Errorf("Search should honour a cancelled context, got %v", err)
	}
}

func TestFullName(t *testing.T) {
	if got := (Patient{FamilyName: "Luna", GivenName: "Ixequi"}).FullName(); got != "Ixequi Luna" {
		t.Errorf("FullName() = %q", got)
	}
	if got := (Patient{FamilyName: "Luna"}).FullName(); got != "Luna" {
		t.Errorf("FullName() with no given name = %q", got)
	}
}

func TestEscapeForPatternNeutralisesRegexMetacharacters(t *testing.T) {
	// A name is user input, and it reaches the database inside a pattern.
	for _, in := range []string{"(a+)+$", "O'Brien.*", "[a-z]", `back\slash`} {
		escaped := EscapeForPattern(in)
		if _, err := regexp.Compile("^" + escaped); err != nil {
			t.Errorf("EscapeForPattern(%q) still produces an invalid pattern: %v", in, err)
		}
		if regexp.MustCompile("^"+escaped).MatchString("aaaaaaab") && in == "(a+)+$" {
			t.Errorf("EscapeForPattern(%q) did not neutralise the quantifier", in)
		}
	}
}
