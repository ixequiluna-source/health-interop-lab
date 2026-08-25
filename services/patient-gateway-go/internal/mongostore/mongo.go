package mongostore

import (
	"context"
	"errors"
	"fmt"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
	"go.mongodb.org/mongo-driver/mongo/readconcern"
	"go.mongodb.org/mongo-driver/mongo/readpref"

	"github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/internal/patient"
)

// Store reads the projection the Kotlin FHIR mapper writes.
//
// This is the read side of the pipeline: it never writes. Reads use majority read concern so
// a query cannot observe an admission that a later rollback removes — a real possibility on
// a replica set during failover, and a confusing one when a clinician saw the patient in the
// console a moment ago and now cannot.
type Store struct {
	patients   *mongo.Collection
	encounters *mongo.Collection
}

func NewStore(db *mongo.Database) *Store {
	opts := options.Collection().SetReadConcern(readconcern.Majority()).
		SetReadPreference(readpref.PrimaryPreferred())
	return &Store{
		patients:   db.Collection("patients", opts),
		encounters: db.Collection("encounters", opts),
	}
}

// EnsureIndexes creates the indexes the queries below depend on.
//
// Called at startup and idempotent. Without the text index, search degrades to a collection
// scan that stays fast on a demo dataset and falls over on a real one.
func (s *Store) EnsureIndexes(ctx context.Context) error {
	_, err := s.patients.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "medicalRecordNumber", Value: 1}},
			Options: options.Index().SetUnique(true).SetName("uniq_mrn"),
		},
		{
			Keys:    bson.D{{Key: "searchTerms", Value: 1}},
			Options: options.Index().SetName("search_terms"),
		},
		{
			// Matches the sort in Search so pagination does not re-sort in memory.
			Keys:    bson.D{{Key: "foldedFamilyName", Value: 1}, {Key: "foldedGivenName", Value: 1}, {Key: "medicalRecordNumber", Value: 1}},
			Options: options.Index().SetName("name_order"),
		},
	})
	if err != nil {
		return fmt.Errorf("create patient indexes: %w", err)
	}

	_, err = s.encounters.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "medicalRecordNumber", Value: 1}, {Key: "admittedAt", Value: -1}},
			Options: options.Index().SetName("encounters_by_patient"),
		},
	})
	if err != nil {
		return fmt.Errorf("create encounter indexes: %w", err)
	}
	return nil
}

type patientDoc struct {
	MedicalRecordNumber string       `bson:"medicalRecordNumber"`
	Identifiers         []identifier `bson:"identifiers"`
	FamilyName          string       `bson:"familyName"`
	GivenName           string       `bson:"givenName"`
	BirthDate           string       `bson:"birthDate"`
	AdministrativeSex   string       `bson:"administrativeSex"`
	LastUpdated         time.Time    `bson:"lastUpdated"`
}

type identifier struct {
	System string `bson:"system"`
	Value  string `bson:"value"`
	Type   string `bson:"type"`
}

type encounterDoc struct {
	VisitNumber         string    `bson:"visitNumber"`
	MedicalRecordNumber string    `bson:"medicalRecordNumber"`
	PatientClass        string    `bson:"patientClass"`
	AdmittedAt          time.Time `bson:"admittedAt"`
	AttendingClinician  string    `bson:"attendingClinician"`
	PointOfCare         string    `bson:"pointOfCare"`
	Room                string    `bson:"room"`
	Bed                 string    `bson:"bed"`
	Facility            string    `bson:"facility"`
}

func (d patientDoc) toDomain() patient.Patient {
	ids := make([]patient.Identifier, 0, len(d.Identifiers))
	for _, id := range d.Identifiers {
		ids = append(ids, patient.Identifier{System: id.System, Value: id.Value, Type: id.Type})
	}
	return patient.Patient{
		MedicalRecordNumber: d.MedicalRecordNumber,
		Identifiers:         ids,
		FamilyName:          d.FamilyName,
		GivenName:           d.GivenName,
		BirthDate:           d.BirthDate,
		AdministrativeSex:   d.AdministrativeSex,
		LastUpdated:         d.LastUpdated,
	}
}

func (s *Store) Get(ctx context.Context, mrn string) (patient.Patient, error) {
	if mrn == "" {
		return patient.Patient{}, fmt.Errorf("%w: medical_record_number is required", patient.ErrInvalidRequest)
	}
	var doc patientDoc
	err := s.patients.FindOne(ctx, bson.D{{Key: "medicalRecordNumber", Value: mrn}}).Decode(&doc)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return patient.Patient{}, patient.ErrNotFound
	}
	if err != nil {
		return patient.Patient{}, fmt.Errorf("find patient: %w", err)
	}
	return doc.toDomain(), nil
}

func (s *Store) Search(ctx context.Context, q patient.SearchQuery) (patient.Page, error) {
	// searchTerms holds pre-folded tokens written by the mapper, so the accent-insensitive
	// match happens on an index rather than through a regular expression over raw names.
	filter := bson.D{{Key: "searchTerms", Value: bson.D{
		{Key: "$regex", Value: "^" + patient.EscapeForPattern(patient.Fold(q.Term))},
	}}}

	total, err := s.patients.CountDocuments(ctx, filter)
	if err != nil {
		return patient.Page{}, fmt.Errorf("count patients: %w", err)
	}

	opts := options.Find().
		SetSort(bson.D{
			{Key: "foldedFamilyName", Value: 1},
			{Key: "foldedGivenName", Value: 1},
			{Key: "medicalRecordNumber", Value: 1},
		}).
		SetSkip(int64(q.Offset)).
		SetLimit(int64(q.PageSize))

	cursor, err := s.patients.Find(ctx, filter, opts)
	if err != nil {
		return patient.Page{}, fmt.Errorf("find patients: %w", err)
	}
	defer func() { _ = cursor.Close(ctx) }()

	patients := make([]patient.Patient, 0, q.PageSize)
	for cursor.Next(ctx) {
		var doc patientDoc
		if err := cursor.Decode(&doc); err != nil {
			return patient.Page{}, fmt.Errorf("decode patient: %w", err)
		}
		patients = append(patients, doc.toDomain())
	}
	if err := cursor.Err(); err != nil {
		return patient.Page{}, fmt.Errorf("iterate patients: %w", err)
	}

	page := patient.Page{Patients: patients, TotalMatched: int(total)}
	if int64(q.Offset+len(patients)) < total {
		page.NextPageToken = patient.EncodeCursor(q.Term, q.Offset+len(patients))
	}
	return page, nil
}

func (s *Store) Encounters(ctx context.Context, mrn string) ([]patient.Encounter, error) {
	if _, err := s.Get(ctx, mrn); err != nil {
		return nil, err
	}
	opts := options.Find().SetSort(bson.D{{Key: "admittedAt", Value: -1}})
	cursor, err := s.encounters.Find(ctx,
		bson.D{{Key: "medicalRecordNumber", Value: mrn}}, opts)
	if err != nil {
		return nil, fmt.Errorf("find encounters: %w", err)
	}
	defer func() { _ = cursor.Close(ctx) }()

	out := make([]patient.Encounter, 0, 8)
	for cursor.Next(ctx) {
		var doc encounterDoc
		if err := cursor.Decode(&doc); err != nil {
			return nil, fmt.Errorf("decode encounter: %w", err)
		}
		out = append(out, patient.Encounter{
			VisitNumber:         doc.VisitNumber,
			MedicalRecordNumber: doc.MedicalRecordNumber,
			PatientClass:        doc.PatientClass,
			AdmittedAt:          doc.AdmittedAt,
			AttendingClinician:  doc.AttendingClinician,
			Location: patient.Location{
				PointOfCare: doc.PointOfCare,
				Room:        doc.Room,
				Bed:         doc.Bed,
				Facility:    doc.Facility,
			},
		})
	}
	return out, cursor.Err()
}

var _ patient.Store = (*Store)(nil)
