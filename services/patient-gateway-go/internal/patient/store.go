package patient

import "context"

// Store is the read side of the platform.
//
// Read-only by construction: writes enter through the HL7 feed, so every change to a patient
// record has an inbound clinical message behind it. A second, unaudited write path is how two
// systems end up disagreeing about a patient with no way to say which one is right.
type Store interface {
	// Get returns ErrNotFound when no patient carries the identifier.
	Get(ctx context.Context, medicalRecordNumber string) (Patient, error)

	// Search returns one page of matches, ordered deterministically.
	Search(ctx context.Context, query SearchQuery) (Page, error)

	// Encounters returns a patient's visits, newest first.
	Encounters(ctx context.Context, medicalRecordNumber string) ([]Encounter, error)
}
