// Package grpcapi adapts the patient domain onto the generated gRPC service.
//
// It is deliberately thin: validation, search, ordering and pagination all live in the
// patient package, which has no knowledge of protobuf. This file only translates shapes and
// maps domain errors onto status codes.
package grpcapi

import (
	"context"
	"errors"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"

	interopv1 "github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/gen/interop/v1"
	"github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/internal/patient"
)

// Server implements interopv1.PatientServiceServer.
type Server struct {
	interopv1.UnimplementedPatientServiceServer

	store  patient.Store
	tracer trace.Tracer
}

func NewServer(store patient.Store, tracer trace.Tracer) *Server {
	return &Server{store: store, tracer: tracer}
}

func (s *Server) GetPatient(
	ctx context.Context, req *interopv1.GetPatientRequest,
) (*interopv1.GetPatientResponse, error) {
	ctx, span := s.tracer.Start(ctx, "PatientService/GetPatient")
	defer span.End()

	// The MRN identifies a patient, so it is PHI and never becomes a span attribute: traces
	// leave the audited boundary for an observability backend. Only its presence is recorded.
	span.SetAttributes(attribute.Bool("patient.identifier_present", req.GetMedicalRecordNumber() != ""))

	p, err := s.store.Get(ctx, req.GetMedicalRecordNumber())
	if err != nil {
		return nil, toStatus(err)
	}
	return &interopv1.GetPatientResponse{Patient: toProtoPatient(p)}, nil
}

func (s *Server) SearchPatients(
	ctx context.Context, req *interopv1.SearchPatientsRequest,
) (*interopv1.SearchPatientsResponse, error) {
	ctx, span := s.tracer.Start(ctx, "PatientService/SearchPatients")
	defer span.End()

	query, err := patient.NewSearchQuery(req.GetQuery(), int(req.GetPageSize()), req.GetPageToken())
	if err != nil {
		return nil, toStatus(err)
	}
	// Page size and result count are safe to record; the search term is not.
	span.SetAttributes(attribute.Int("search.page_size", query.PageSize))

	page, err := s.store.Search(ctx, query)
	if err != nil {
		return nil, toStatus(err)
	}
	span.SetAttributes(attribute.Int("search.total_matched", page.TotalMatched))

	out := make([]*interopv1.Patient, 0, len(page.Patients))
	for _, p := range page.Patients {
		out = append(out, toProtoPatient(p))
	}
	return &interopv1.SearchPatientsResponse{
		Patients:      out,
		NextPageToken: page.NextPageToken,
		TotalMatched:  int32(page.TotalMatched),
	}, nil
}

func (s *Server) StreamEncounters(
	req *interopv1.StreamEncountersRequest,
	stream interopv1.PatientService_StreamEncountersServer,
) error {
	ctx, span := s.tracer.Start(stream.Context(), "PatientService/StreamEncounters")
	defer span.End()

	encounters, err := s.store.Encounters(ctx, req.GetMedicalRecordNumber())
	if err != nil {
		return toStatus(err)
	}
	span.SetAttributes(attribute.Int("encounters.count", len(encounters)))

	for _, e := range encounters {
		// Stop promptly when the client goes away rather than serialising the whole
		// history into a dead stream.
		if err := ctx.Err(); err != nil {
			return toStatus(err)
		}
		if err := stream.Send(toProtoEncounter(e)); err != nil {
			return err
		}
	}
	return nil
}

// toStatus maps domain errors onto gRPC codes.
//
// The default is Internal, not InvalidArgument: an unrecognised error is a bug in this
// service, and reporting it as the caller's fault sends them chasing a request that was fine.
func toStatus(err error) error {
	switch {
	case errors.Is(err, patient.ErrNotFound):
		return status.Error(codes.NotFound, "patient not found")
	case errors.Is(err, patient.ErrInvalidCursor):
		return status.Error(codes.InvalidArgument, "invalid page token")
	case errors.Is(err, patient.ErrInvalidRequest):
		// The domain's message describes the rule, not the data, so it is safe to return.
		return status.Error(codes.InvalidArgument, err.Error())
	case errors.Is(err, context.Canceled):
		return status.Error(codes.Canceled, "request cancelled")
	case errors.Is(err, context.DeadlineExceeded):
		return status.Error(codes.DeadlineExceeded, "request deadline exceeded")
	default:
		// Deliberately opaque: the detail goes to logs and traces, not to the caller.
		return status.Error(codes.Internal, "internal error")
	}
}

func toProtoPatient(p patient.Patient) *interopv1.Patient {
	ids := make([]*interopv1.Identifier, 0, len(p.Identifiers))
	for _, id := range p.Identifiers {
		ids = append(ids, &interopv1.Identifier{
			System: id.System,
			Value:  id.Value,
			Type:   id.Type,
		})
	}
	out := &interopv1.Patient{
		MedicalRecordNumber: p.MedicalRecordNumber,
		Identifiers:         ids,
		FamilyName:          p.FamilyName,
		GivenName:           p.GivenName,
		BirthDate:           p.BirthDate,
		AdministrativeSex:   p.AdministrativeSex,
	}
	if !p.LastUpdated.IsZero() {
		out.LastUpdated = timestamppb.New(p.LastUpdated)
	}
	return out
}

func toProtoEncounter(e patient.Encounter) *interopv1.Encounter {
	out := &interopv1.Encounter{
		VisitNumber:         e.VisitNumber,
		MedicalRecordNumber: e.MedicalRecordNumber,
		PatientClass:        e.PatientClass,
		AttendingClinician:  e.AttendingClinician,
		Location: &interopv1.Location{
			PointOfCare: e.Location.PointOfCare,
			Room:        e.Location.Room,
			Bed:         e.Location.Bed,
			Facility:    e.Location.Facility,
		},
	}
	if !e.AdmittedAt.IsZero() {
		out.AdmittedAt = timestamppb.New(e.AdmittedAt)
	}
	return out
}
