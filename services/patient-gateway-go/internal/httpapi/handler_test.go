package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	interopv1 "github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/gen/interop/v1"
	"github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/internal/grpcapi"
	"github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/internal/patient"
	"go.opentelemetry.io/otel/trace/noop"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/health"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func fixture(t *testing.T) (http.Handler, *grpc.Server) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	store := patient.NewMemoryStore()
	for _, id := range []string{"DEMO-01", "DEMO-02"} {
		store.Put(patient.Patient{MedicalRecordNumber: id, FamilyName: "Demo", GivenName: id, LastUpdated: time.Date(2026, 9, 19, 0, 0, 0, 0, time.UTC)})
	}
	store.AddEncounter(patient.Encounter{MedicalRecordNumber: "DEMO-01", VisitNumber: "VISIT-01", PatientClass: "I", AdmittedAt: time.Date(2026, 9, 19, 0, 0, 0, 0, time.UTC)})
	s := grpc.NewServer()
	interopv1.RegisterPatientServiceServer(s, grpcapi.NewServer(store, noop.NewTracerProvider().Tracer("test")))
	healthServer := health.NewServer()
	healthServer.SetServingStatus("interop.v1.PatientService", healthpb.HealthCheckResponse_SERVING)
	healthpb.RegisterHealthServer(s, healthServer)
	go s.Serve(ln)
	t.Cleanup(s.Stop)
	conn, err := grpc.NewClient(ln.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return New(conn), s
}
func request(h http.Handler, path, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", path, bytes.NewBufferString(body))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}
func TestRealGRPCQueriesAndPagination(t *testing.T) {
	h, _ := fixture(t)
	w := request(h, "/interop.v1.PatientService/SearchPatients", `{"query":"demo","pageSize":1}`)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body)
	}
	var page struct {
		Patients []map[string]any `json:"patients"`
		Next     string           `json:"nextPageToken"`
		Total    int              `json:"totalMatched"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	if page.Total != 2 || len(page.Patients) != 1 || page.Next == "" {
		t.Fatal(w.Body)
	}
	first := page.Patients[0]["medicalRecordNumber"]
	b, _ := json.Marshal(map[string]any{"query": "demo", "pageSize": 1, "pageToken": page.Next})
	w = request(h, "/interop.v1.PatientService/SearchPatients", string(b))
	page.Next = ""
	_ = json.Unmarshal(w.Body.Bytes(), &page)
	if w.Code != 200 || len(page.Patients) != 1 || first == page.Patients[0]["medicalRecordNumber"] {
		t.Fatal(w.Body)
	}
	w = request(h, "/interop.v1.PatientService/GetPatient", `{"medicalRecordNumber":"DEMO-01"}`)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "2026-09-19T00:00:00Z") {
		t.Fatal(w.Code, w.Body)
	}
	w = request(h, "/interop.v1.PatientService/ListEncounters", `{"medicalRecordNumber":"DEMO-01"}`)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "VISIT-01") {
		t.Fatal(w.Code, w.Body)
	}
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("patient data must not be cached")
	}
}
func TestErrorsAreVisibleAndBounded(t *testing.T) {
	h, s := fixture(t)
	for _, tc := range []struct {
		path, body string
		want       int
	}{
		{"GetPatient", `{"medicalRecordNumber":"absent"}`, 404},
		{"SearchPatients", `{"query":"x"}`, 400},
		{"SearchPatients", `{"query":"demo","pageToken":"invalid"}`, 400},
		{"GetPatient", `{"unknown":true}`, 400},
		{"GetPatient", `{`, 400},
		{"GetPatient", strings.Repeat("x", 8193), 413},
	} {
		w := request(h, "/interop.v1.PatientService/"+tc.path, tc.body)
		if w.Code != tc.want {
			t.Errorf("%s: %d want %d", tc.path, w.Code, tc.want)
		}
	}
	s.Stop()
	w := request(h, "/interop.v1.PatientService/GetPatient", `{"medicalRecordNumber":"DEMO-01"}`)
	if w.Code != 503 && w.Code != 504 {
		t.Fatal(w.Code, w.Body)
	}
}
func TestOriginAndContentType(t *testing.T) {
	h, _ := fixture(t)
	for _, tc := range []struct {
		origin, kind string
		want         int
	}{{"https://evil.example", "application/json", 403}, {"", "text/plain", 415}} {
		r := httptest.NewRequest("POST", "/interop.v1.PatientService/GetPatient", strings.NewReader(`{}`))
		r.Header.Set("Origin", tc.origin)
		r.Header.Set("Content-Type", tc.kind)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tc.want {
			t.Fatal(w.Code)
		}
	}
}
func TestUnknownUpstreamNeverLooksHealthy(t *testing.T) {
	h, s := fixture(t)
	probe := func() []map[string]any {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", "/ops/pipeline-status", nil))
		var out struct {
			Services []map[string]any `json:"services"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
			t.Fatal(err)
		}
		return out.Services
	}
	rows := probe()
	if rows[0]["health"] != "unknown" || rows[2]["health"] != "healthy" || rows[0]["counters"] != nil {
		t.Fatal(rows)
	}
	s.Stop()
	rows = probe()
	if rows[2]["health"] != "down" {
		t.Fatal(rows)
	}
}
func TestCancelledRequest(t *testing.T) {
	h, _ := fixture(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	r := httptest.NewRequest("POST", "/interop.v1.PatientService/GetPatient", strings.NewReader(`{"medicalRecordNumber":"DEMO-01"}`)).WithContext(ctx)
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code == 200 {
		t.Fatal("cancelled request succeeded")
	}
}
