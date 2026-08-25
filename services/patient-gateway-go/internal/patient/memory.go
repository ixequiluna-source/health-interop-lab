package patient

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"sync"
)

// MemoryStore is an in-memory Store used by tests and by the demo pipeline.
//
// It is a full implementation of the contract rather than a stub, including ordering and
// pagination, so the tests that run against it are testing the same rules the Mongo-backed
// store must honour.
type MemoryStore struct {
	mu         sync.RWMutex
	patients   map[string]Patient
	encounters map[string][]Encounter
}

func NewMemoryStore() *MemoryStore {
	return &MemoryStore{
		patients:   make(map[string]Patient),
		encounters: make(map[string][]Encounter),
	}
}

// Put inserts or replaces a patient.
func (s *MemoryStore) Put(p Patient) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.patients[p.MedicalRecordNumber] = p
}

// AddEncounter appends a visit for a patient.
func (s *MemoryStore) AddEncounter(e Encounter) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.encounters[e.MedicalRecordNumber] = append(s.encounters[e.MedicalRecordNumber], e)
}

func (s *MemoryStore) Get(ctx context.Context, mrn string) (Patient, error) {
	if err := ctx.Err(); err != nil {
		return Patient{}, err
	}
	if strings.TrimSpace(mrn) == "" {
		return Patient{}, fmt.Errorf("%w: medical_record_number is required", ErrInvalidRequest)
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	p, ok := s.patients[mrn]
	if !ok {
		return Patient{}, ErrNotFound
	}
	return p, nil
}

func (s *MemoryStore) Search(ctx context.Context, q SearchQuery) (Page, error) {
	if err := ctx.Err(); err != nil {
		return Page{}, err
	}
	s.mu.RLock()
	matched := make([]Patient, 0, len(s.patients))
	for _, p := range s.patients {
		if p.Matches(q.Term) {
			matched = append(matched, p)
		}
	}
	s.mu.RUnlock()

	// Map iteration order is randomised in Go, so an explicit total order is required for
	// pagination to be stable. Without it, consecutive pages of the same search can repeat
	// and omit patients — the kind of bug that only shows up once a result set outgrows
	// one page.
	sort.Slice(matched, func(i, j int) bool {
		if matched[i].FamilyName != matched[j].FamilyName {
			return Fold(matched[i].FamilyName) < Fold(matched[j].FamilyName)
		}
		if matched[i].GivenName != matched[j].GivenName {
			return Fold(matched[i].GivenName) < Fold(matched[j].GivenName)
		}
		return matched[i].MedicalRecordNumber < matched[j].MedicalRecordNumber
	})

	total := len(matched)
	if q.Offset >= total {
		return Page{Patients: []Patient{}, TotalMatched: total}, nil
	}
	end := q.Offset + q.PageSize
	if end > total {
		end = total
	}
	page := Page{
		Patients:     append([]Patient(nil), matched[q.Offset:end]...),
		TotalMatched: total,
	}
	if end < total {
		page.NextPageToken = EncodeCursor(q.Term, end)
	}
	return page, nil
}

func (s *MemoryStore) Encounters(ctx context.Context, mrn string) ([]Encounter, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	if _, ok := s.patients[mrn]; !ok {
		return nil, ErrNotFound
	}
	out := append([]Encounter(nil), s.encounters[mrn]...)
	sort.Slice(out, func(i, j int) bool { return out[i].AdmittedAt.After(out[j].AdmittedAt) })
	return out, nil
}

var _ Store = (*MemoryStore)(nil)
