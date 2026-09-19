// console-edge is a local-demo HTTP adapter for PatientService.
package main

import (
	"context"
	"errors"
	"github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go/internal/httpapi"
	"go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	target := os.Getenv("GRPC_TARGET")
	if target == "" {
		target = "127.0.0.1:9090"
	}
	addr := os.Getenv("EDGE_ADDR")
	if addr == "" {
		addr = "127.0.0.1:8084"
	}
	conn, err := grpc.NewClient(target, grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithStatsHandler(otelgrpc.NewClientHandler()))
	if err != nil {
		log.Fatal("gRPC client configuration failed")
	}
	defer conn.Close()
	server := &http.Server{Addr: addr, Handler: httpapi.New(conn), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 10 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 16 * 1024}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}()
	log.Print("Synthetic-demo console adapter listening on ", addr)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal("HTTP listener failed")
	}
}
