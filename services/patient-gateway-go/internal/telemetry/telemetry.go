// Package telemetry wires OpenTelemetry tracing for the gateway.
//
// The whole platform speaks OTLP to a collector rather than to a vendor SDK, so the backend
// is a collector configuration change rather than a code change in five services written in
// five languages. That is the practical argument for OpenTelemetry in a polyglot system.
package telemetry

import (
	"context"
	"errors"
	"fmt"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
	"go.opentelemetry.io/otel/trace/noop"
)

// Config describes how the process reports traces.
type Config struct {
	ServiceName    string
	ServiceVersion string
	Environment    string
	// OTLPEndpoint is host:port. Empty disables export entirely.
	OTLPEndpoint string
	// SampleRatio is the head-sampling probability for root spans, 0..1.
	SampleRatio float64
}

// Shutdown flushes pending spans. Always non-nil, so callers can defer it unconditionally.
type Shutdown func(context.Context) error

// Setup installs a global tracer provider and propagator.
//
// With no endpoint configured it installs a no-op provider rather than failing: a missing
// observability backend must not stop a clinical service from starting.
func Setup(ctx context.Context, cfg Config) (trace.Tracer, Shutdown, error) {
	if cfg.OTLPEndpoint == "" {
		otel.SetTracerProvider(noop.NewTracerProvider())
		installPropagator()
		return noop.NewTracerProvider().Tracer(cfg.ServiceName),
			func(context.Context) error { return nil }, nil
	}

	exporter, err := otlptracegrpc.New(ctx,
		otlptracegrpc.WithEndpoint(cfg.OTLPEndpoint),
		// The collector is a neighbour inside the mesh; TLS terminates at the mesh boundary.
		otlptracegrpc.WithInsecure(),
		otlptracegrpc.WithTimeout(10*time.Second),
	)
	if err != nil {
		return nil, nil, fmt.Errorf("create OTLP exporter: %w", err)
	}

	res, err := resource.Merge(
		resource.Default(),
		resource.NewWithAttributes(
			semconv.SchemaURL,
			semconv.ServiceName(cfg.ServiceName),
			semconv.ServiceVersion(cfg.ServiceVersion),
			attribute.String("deployment.environment", cfg.Environment),
		),
	)
	if err != nil {
		return nil, nil, fmt.Errorf("build resource: %w", err)
	}

	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter,
			sdktrace.WithBatchTimeout(5*time.Second),
			sdktrace.WithMaxQueueSize(2048),
		),
		sdktrace.WithResource(res),
		// ParentBased keeps a trace intact end to end: once the Java ingest service samples
		// a message, every downstream span for it is sampled too. Sampling per service
		// independently produces traces with holes, which are worse than no traces.
		sdktrace.WithSampler(sdktrace.ParentBased(
			sdktrace.TraceIDRatioBased(clampRatio(cfg.SampleRatio)),
		)),
	)
	otel.SetTracerProvider(provider)
	installPropagator()

	shutdown := func(ctx context.Context) error {
		return errors.Join(provider.Shutdown(ctx), exporter.Shutdown(ctx))
	}
	return provider.Tracer(cfg.ServiceName), shutdown, nil
}

// installPropagator sets W3C trace context plus baggage.
//
// Without a propagator, each service starts its own trace and the pipeline appears as five
// unrelated traces instead of one — precisely the question you are trying to answer when an
// admission does not show up in the console.
func installPropagator() {
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))
}

func clampRatio(r float64) float64 {
	switch {
	case r <= 0:
		return 0
	case r >= 1:
		return 1
	default:
		return r
	}
}
