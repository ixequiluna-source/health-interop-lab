// Command patient-gateway serves the read side of the interoperability platform over gRPC.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"go.mongodb.org/mongo-driver/mongo"
	mongoopts "go.mongodb.org/mongo-driver/mongo/options"
	"go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc"
	"google.golang.org/grpc"
	"google.golang.org/grpc/health"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/keepalive"
	"google.golang.org/grpc/reflection"

	interopv1 "github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/gen/interop/v1"
	"github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/internal/grpcapi"
	"github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/internal/mongostore"
	"github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/internal/patient"
	"github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/internal/telemetry"
)

const serviceVersion = "1.0.0"

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	// The runtime image is distroless: no shell, no curl, no wget. The binary therefore has
	// to be able to check its own health, or the image needs a package manager back — which
	// is exactly the pivot surface distroless exists to remove.
	if len(os.Args) > 1 && os.Args[1] == "-healthcheck" {
		os.Exit(healthcheck())
	}

	if err := run(logger); err != nil {
		logger.Error("fatal", slog.String("error", err.Error()))
		os.Exit(1)
	}
}

func run(logger *slog.Logger) error {
	// Cancelled on SIGTERM, which is what Kubernetes sends before the grace period starts.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	tracer, shutdownTracing, err := telemetry.Setup(ctx, telemetry.Config{
		ServiceName:    env("OTEL_SERVICE_NAME", "patient-gateway"),
		ServiceVersion: serviceVersion,
		Environment:    env("DEPLOYMENT_ENV", "local"),
		OTLPEndpoint:   env("OTEL_EXPORTER_OTLP_ENDPOINT", ""),
		SampleRatio:    envFloat("OTEL_TRACES_SAMPLER_ARG", 0.1),
	})
	if err != nil {
		return fmt.Errorf("telemetry: %w", err)
	}
	defer func() {
		flushCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := shutdownTracing(flushCtx); err != nil {
			logger.Warn("tracer shutdown", slog.String("error", err.Error()))
		}
	}()

	store, cleanup, err := openStore(ctx, logger)
	if err != nil {
		return err
	}
	defer cleanup()

	server := grpc.NewServer(
		grpc.StatsHandler(otelgrpc.NewServerHandler()),
		// Bound how long a single call may run. Without this, one stuck query holds a
		// connection open indefinitely and the pool drains under load.
		grpc.ConnectionTimeout(15*time.Second),
		grpc.KeepaliveParams(keepalive.ServerParameters{
			MaxConnectionIdle: 5 * time.Minute,
			Time:              30 * time.Second,
			Timeout:           10 * time.Second,
		}),
		grpc.KeepaliveEnforcementPolicy(keepalive.EnforcementPolicy{
			MinTime:             15 * time.Second,
			PermitWithoutStream: true,
		}),
		grpc.MaxRecvMsgSize(4*1024*1024),
	)
	interopv1.RegisterPatientServiceServer(server, grpcapi.NewServer(store, tracer))

	healthServer := health.NewServer()
	healthpb.RegisterHealthServer(server, healthServer)
	healthServer.SetServingStatus("interop.v1.PatientService", healthpb.HealthCheckResponse_SERVING)

	// Reflection is a development convenience and an information disclosure in production:
	// it hands an unauthenticated caller the full service and message schema.
	if env("GRPC_REFLECTION", "false") == "true" {
		reflection.Register(server)
		logger.Warn("gRPC reflection is enabled; do not enable it in production")
	}

	addr := ":" + env("GRPC_PORT", "9090")
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("listen on %s: %w", addr, err)
	}

	metricsServer := startMetricsServer(env("HTTP_PORT", "8080"), logger)

	serveErr := make(chan error, 1)
	go func() {
		logger.Info("gRPC listening", slog.String("addr", addr))
		serveErr <- server.Serve(listener)
	}()

	select {
	case err := <-serveErr:
		if err != nil && !errors.Is(err, grpc.ErrServerStopped) {
			return fmt.Errorf("serve: %w", err)
		}
	case <-ctx.Done():
		logger.Info("shutdown signal received; draining")
		// Report NOT_SERVING first so load balancers stop sending new work, then drain.
		healthServer.SetServingStatus("interop.v1.PatientService",
			healthpb.HealthCheckResponse_NOT_SERVING)

		drained := make(chan struct{})
		go func() { server.GracefulStop(); close(drained) }()
		select {
		case <-drained:
		case <-time.After(20 * time.Second):
			logger.Warn("graceful stop timed out; forcing")
			server.Stop()
		}
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = metricsServer.Shutdown(shutdownCtx)
	}
	return nil
}

// openStore returns the Mongo-backed store, or an in-memory one seeded for local runs.
func openStore(ctx context.Context, logger *slog.Logger) (patient.Store, func(), error) {
	uri := env("MONGODB_URI", "")
	if uri == "" {
		logger.Warn("MONGODB_URI is unset; serving an in-memory store with sample data")
		return seededMemoryStore(), func() {}, nil
	}

	connectCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	client, err := mongo.Connect(connectCtx, mongoopts.Client().ApplyURI(uri))
	if err != nil {
		return nil, nil, fmt.Errorf("connect to mongodb: %w", err)
	}
	if err := client.Ping(connectCtx, nil); err != nil {
		return nil, nil, fmt.Errorf("ping mongodb: %w", err)
	}

	store := mongostore.NewStore(client.Database(env("MONGODB_DATABASE", "interop")))
	indexCtx, cancelIndex := context.WithTimeout(ctx, 30*time.Second)
	defer cancelIndex()
	if err := store.EnsureIndexes(indexCtx); err != nil {
		return nil, nil, err
	}

	cleanup := func() {
		disconnectCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = client.Disconnect(disconnectCtx)
	}
	return store, cleanup, nil
}

func seededMemoryStore() *patient.MemoryStore {
	store := patient.NewMemoryStore()
	store.Put(patient.Patient{
		MedicalRecordNumber: "MRN-88213",
		Identifiers:         []patient.Identifier{{System: "IMSS", Value: "NSS-4471120", Type: "SS"}},
		FamilyName:          "Luna",
		GivenName:           "Ixequi",
		BirthDate:           "1990-03-14",
		AdministrativeSex:   "M",
		LastUpdated:         time.Now().UTC(),
	})
	store.AddEncounter(patient.Encounter{
		VisitNumber:         "VN-556677",
		MedicalRecordNumber: "MRN-88213",
		PatientClass:        "I",
		AdmittedAt:          time.Date(2026, 8, 25, 14, 30, 0, 0, time.UTC),
		AttendingClinician:  "Enrique Torres",
		Location: patient.Location{
			PointOfCare: "WARD-3", Room: "301", Bed: "A", Facility: "HGS_PUEBLA",
		},
	})
	return store
}

func startMetricsServer(port string, logger *slog.Logger) *http.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	})
	server := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}
	go func() {
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("metrics server", slog.String("error", err.Error()))
		}
	}()
	return server
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envFloat(key string, fallback float64) float64 {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	parsed, err := strconv.ParseFloat(v, 64)
	if err != nil {
		return fallback
	}
	return parsed
}

// healthcheck probes the local health endpoint and returns a process exit code.
//
// Used by the container HEALTHCHECK, since the distroless runtime image has no HTTP client
// of its own.
func healthcheck() int {
	client := &http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get("http://127.0.0.1:" + env("HTTP_PORT", "8080") + "/healthz")
	if err != nil {
		return 1
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
