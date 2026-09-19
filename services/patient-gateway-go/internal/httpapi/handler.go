// Package httpapi exposes the console's bounded JSON contract over a real gRPC client.
package httpapi

import (
	"context"
	"encoding/json"
	"io"
	"mime"
	"net/http"
	"net/url"
	"time"

	interopv1 "github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/gen/interop/v1"
	"go.opentelemetry.io/otel/propagation"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
)

// Handler intentionally implements only the JSON endpoints used by the console,
// not the complete Connect streaming protocol. It must sit behind authentication
// before use with anything other than isolated synthetic demo data.
type Handler struct {
	patient interopv1.PatientServiceClient
	health  healthpb.HealthClient
}

func New(conn grpc.ClientConnInterface) http.Handler {
	return &Handler{interopv1.NewPatientServiceClient(conn), healthpb.NewHealthClient(conn)}
}
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if origin := r.Header.Get("Origin"); origin != "" {
		u, err := url.Parse(origin)
		if err != nil || u.Host != r.Host || (u.Scheme != "http" && u.Scheme != "https") {
			writeError(w, status.Error(codes.PermissionDenied, "origin not allowed"))
			return
		}
	}
	ctx := propagation.TraceContext{}.Extract(r.Context(), propagation.HeaderCarrier(r.Header))
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if r.URL.Path == "/healthz" && r.Method == http.MethodGet {
		_, _ = w.Write([]byte(`{"status":"ok"}`))
		return
	}
	if r.URL.Path == "/ops/pipeline-status" && r.Method == http.MethodGet {
		h.pipeline(ctx, w)
		return
	}
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		w.WriteHeader(http.StatusMethodNotAllowed)
		_, _ = w.Write([]byte(`{"code":"invalid_argument","message":"POST required"}`))
		return
	}
	kind, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || kind != "application/json" {
		w.WriteHeader(http.StatusUnsupportedMediaType)
		_, _ = w.Write([]byte(`{"code":"invalid_argument","message":"JSON required"}`))
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 8192))
	if err != nil {
		w.WriteHeader(http.StatusRequestEntityTooLarge)
		_, _ = w.Write([]byte(`{"code":"resource_exhausted","message":"request too large"}`))
		return
	}
	decode := func(m proto.Message) bool {
		if err := protojson.Unmarshal(body, m); err != nil {
			writeError(w, status.Error(codes.InvalidArgument, "invalid request body"))
			return false
		}
		return true
	}
	switch r.URL.Path {
	case "/interop.v1.PatientService/GetPatient":
		req := new(interopv1.GetPatientRequest)
		if !decode(req) {
			return
		}
		out, err := h.patient.GetPatient(ctx, req)
		writeProto(w, out, err)
	case "/interop.v1.PatientService/SearchPatients":
		req := new(interopv1.SearchPatientsRequest)
		if !decode(req) {
			return
		}
		out, err := h.patient.SearchPatients(ctx, req)
		writeProto(w, out, err)
	case "/interop.v1.PatientService/ListEncounters":
		req := new(interopv1.StreamEncountersRequest)
		if !decode(req) {
			return
		}
		stream, err := h.patient.StreamEncounters(ctx, req)
		if err != nil {
			writeError(w, err)
			return
		}
		rows := make([]json.RawMessage, 0)
		size := 0
		for {
			row, err := stream.Recv()
			if err == io.EOF {
				break
			}
			if err != nil {
				writeError(w, err)
				return
			}
			data, err := protojson.Marshal(row)
			if err != nil {
				writeError(w, err)
				return
			}
			size += len(data)
			if len(rows) >= 1000 || size > 4*1024*1024 {
				writeError(w, status.Error(codes.ResourceExhausted, "encounter result limit exceeded"))
				return
			}
			rows = append(rows, data)
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"encounters": rows})
	default:
		writeError(w, status.Error(codes.NotFound, "endpoint not found"))
	}
}
func writeProto(w http.ResponseWriter, m proto.Message, err error) {
	if err != nil {
		writeError(w, err)
		return
	}
	data, err := protojson.Marshal(m)
	if err != nil {
		writeError(w, err)
		return
	}
	_, _ = w.Write(data)
}
func writeError(w http.ResponseWriter, err error) {
	code, message, httpCode := "internal", "request could not be completed", 500
	switch status.Code(err) {
	case codes.InvalidArgument:
		code, message, httpCode = "invalid_argument", "check the request fields", 400
	case codes.NotFound:
		code, message, httpCode = "not_found", "record or endpoint not found", 404
	case codes.PermissionDenied:
		code, message, httpCode = "permission_denied", "request not allowed", 403
	case codes.Unavailable:
		code, message, httpCode = "unavailable", "gateway unavailable", 503
	case codes.DeadlineExceeded:
		code, message, httpCode = "deadline_exceeded", "gateway deadline exceeded", 504
	case codes.ResourceExhausted:
		code, message, httpCode = "resource_exhausted", "result exceeds the supported limit", 429
	case codes.Canceled:
		code, message, httpCode = "cancelled", "request cancelled", 408
	}
	w.WriteHeader(httpCode)
	_ = json.NewEncoder(w).Encode(map[string]string{"code": code, "message": message})
}
func (h *Handler) pipeline(ctx context.Context, w http.ResponseWriter) {
	now := time.Now().UTC().Format(time.RFC3339)
	type service struct {
		ID       string `json:"id"`
		Name     string `json:"displayName"`
		Runtime  string `json:"runtime"`
		Stage    string `json:"stage"`
		Health   string `json:"health"`
		Detail   string `json:"detail"`
		Checked  string `json:"checkedAt"`
		Counters any    `json:"counters"`
	}
	rows := []service{
		{"hl7-ingest", "HL7 ingest", "Java", "ingest", "unknown", "Not probed by this read-side demo; message delivery is unverified.", now, nil},
		{"fhir-mapper", "FHIR mapper", "Kotlin", "transform", "unknown", "Not probed by this read-side demo; persistence is unverified.", now, nil},
		{"patient-gateway", "Patient gateway", "Go / gRPC", "read", "unknown", "", now, nil},
		{"console-edge", "Console adapter", "Go / HTTP", "edge", "healthy", "This adapter answered the request.", now, nil},
		{"claims-edi", "Claims worker", "C#", "claims", "unknown", "Not probed; no claim-delivery evidence is available.", now, nil},
	}
	check, err := h.health.Check(ctx, &healthpb.HealthCheckRequest{Service: "interop.v1.PatientService"})
	if err != nil {
		rows[2].Health = "down"
		rows[2].Detail = "gRPC health probe failed; patient queries may be unavailable."
	} else if check.GetStatus() != healthpb.HealthCheckResponse_SERVING {
		rows[2].Health = "degraded"
		rows[2].Detail = "gRPC reports not serving."
	} else {
		rows[2].Health = "healthy"
		rows[2].Detail = "gRPC reports serving. This does not verify upstream ingestion or persistence."
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"services": rows, "observedAt": now})
}
