// Package patient holds the gateway's domain logic: the patient read model, search,
// pagination and the validation rules that guard it.
//
// Nothing here imports generated protobuf or a database driver. Keeping the rules in plain
// Go means they can be tested directly, and it keeps the wire format and the storage engine
// as replaceable details rather than assumptions baked through the service.
package patient

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// Sentinel errors the transport layer maps onto status codes.
var (
	ErrNotFound       = errors.New("patient not found")
	ErrInvalidRequest = errors.New("invalid request")
	ErrInvalidCursor  = errors.New("invalid page token")
)

// Pagination bounds. The cap is a disclosure control, not a performance tweak: an uncapped
// page size lets one request pull the entire patient index in a single response.
const (
	DefaultPageSize = 25
	MaxPageSize     = 100
	MinQueryLength  = 2
)

// Identifier is one assigning-authority-scoped patient identifier.
type Identifier struct {
	System string
	Value  string
	Type   string
}

// Patient is the read model the FHIR mapper maintains.
type Patient struct {
	MedicalRecordNumber string
	Identifiers         []Identifier
	FamilyName          string
	GivenName           string
	// BirthDate is an ISO 8601 string, possibly partial ("1990", "1990-03", "1990-03-14"),
	// because HL7 permits partial dates. A time.Time here would force an invented day.
	BirthDate         string
	AdministrativeSex string
	LastUpdated       time.Time
}

// Location is a compound point of care.
type Location struct {
	PointOfCare string
	Room        string
	Bed         string
	Facility    string
}

// Encounter is one visit.
type Encounter struct {
	VisitNumber         string
	MedicalRecordNumber string
	PatientClass        string
	AdmittedAt          time.Time
	AttendingClinician  string
	Location            Location
}

// SearchQuery is a validated, normalised search request.
type SearchQuery struct {
	Term     string
	PageSize int
	Offset   int
}

// Page is one slice of search results.
type Page struct {
	Patients      []Patient
	NextPageToken string
	TotalMatched  int
}

// NewSearchQuery validates and normalises the inputs of a search.
//
// Page size zero means "unspecified" and takes the default; anything above the cap is
// clamped rather than rejected, because a client asking for too much should still get a
// useful answer instead of an error it cannot act on.
func NewSearchQuery(term string, pageSize int, pageToken string) (SearchQuery, error) {
	trimmed := strings.TrimSpace(term)
	if len([]rune(trimmed)) < MinQueryLength {
		return SearchQuery{}, fmt.Errorf(
			"%w: query must be at least %d characters", ErrInvalidRequest, MinQueryLength)
	}
	if pageSize < 0 {
		return SearchQuery{}, fmt.Errorf("%w: page_size cannot be negative", ErrInvalidRequest)
	}
	switch {
	case pageSize == 0:
		pageSize = DefaultPageSize
	case pageSize > MaxPageSize:
		pageSize = MaxPageSize
	}

	offset := 0
	if pageToken != "" {
		cursor, err := DecodeCursor(pageToken)
		if err != nil {
			return SearchQuery{}, err
		}
		// A cursor is only meaningful for the query that produced it. Reusing one across
		// queries silently returns the wrong slice of a different result set.
		if cursor.TermHash != HashTerm(trimmed) {
			return SearchQuery{}, fmt.Errorf(
				"%w: page token belongs to a different query", ErrInvalidCursor)
		}
		offset = cursor.Offset
	}

	return SearchQuery{Term: trimmed, PageSize: pageSize, Offset: offset}, nil
}

// Matches reports whether a patient satisfies the search term.
//
// Matching is case-insensitive and diacritic-insensitive: "Nunez" must find "Núñez". Mexican
// and Spanish surnames routinely arrive from one system with accents and from another
// without, and an exact-match search quietly reports "no such patient" for a patient who is
// admitted.
func (p Patient) Matches(term string) bool {
	needle := Fold(term)
	if needle == "" {
		return false
	}
	haystacks := []string{p.FamilyName, p.GivenName, p.MedicalRecordNumber}
	for _, id := range p.Identifiers {
		haystacks = append(haystacks, id.Value)
	}
	for _, h := range haystacks {
		if strings.Contains(Fold(h), needle) {
			return true
		}
	}
	return false
}

// FullName renders "Given Family", omitting whichever part is absent.
func (p Patient) FullName() string {
	return strings.TrimSpace(p.GivenName + " " + p.FamilyName)
}
