# Connected synthetic console

This demo exercises **Angular → HTTP JSON → gRPC → seeded Go store**. It does not claim HL7 ingestion, MongoDB persistence or claim delivery. Use synthetic data only. The adapter binds to loopback by default, uses plaintext gRPC locally and supplies no authentication; it is not a public clinical endpoint.

## Prerequisites

Go 1.25+, protoc 28.3, Node.js 22 and npm. From the Go service directory, install the pinned generators, place your Go bin directory on PATH, then generate the stubs:

```sh
cd services/patient-gateway-go
go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.5
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.5.1
make proto
go mod download
go test ./...
```

Use three terminals, starting each at the repository root:

```sh
# Terminal 1: no MONGODB_URI -> synthetic in-memory store
cd services/patient-gateway-go
go run .
```

```sh
# Terminal 2: bounded HTTP adapter on 127.0.0.1:8084
cd services/patient-gateway-go
go run ./cmd/console-edge
```

```sh
# Terminal 3: same-origin development proxy
cd web/console-angular
npm ci
npm run start:connected
```

Open http://localhost:4200. Search for `Luna`, open `MRN-88213` and inspect `VN-556677`. These are seeded example values. The top bar identifies the connected synthetic mode.

The Pipeline page probes the real gRPC health endpoint. Ingest, mapper and claims remain **unknown** because this read-side demo does not measure them. Counters remain absent, never invented zeroes. Health means that a service answered its probe, not that the complete pipeline is delivering messages.

## Failure checks

- Search with an invalid cursor: HTTP 400 with a bounded error envelope.
- Open a missing patient: HTTP 404 and an explicit not-found view.
- Stop the gateway: queries return 503/504; the gateway health card becomes down.
- Change input while a request is pending: the existing console store rejects stale results.
- Large request bodies are rejected above 8 KiB; streamed encounter lists stop at 1,000 entries or 4 MiB, with an explicit error instead of silent truncation.

The adapter implements the console's JSON subset, **not a complete Connect protocol server**. Requests have three-second deadlines. It does not return raw backend errors or log patient request bodies. Configure `GRPC_TARGET` and `EDGE_ADDR` only for trusted internal environments; authentication, authorization and TLS are separate deployment work.

## Repeatable evidence

[The connected-demo workflow](../.github/workflows/connected-demo.yml) generates protobuf stubs, tests the Go packages with the race detector, starts both Go processes and Angular, then exercises real browser requests. [The test](../tools/connected-demo.cjs) captures synthetic screenshots as a workflow artifact. It is distinct from the static illustrative walkthrough on the personal website.

For a Windows setup without Make, execute the protoc command from the [Makefile](../services/patient-gateway-go/Makefile) directly after adding the Go bin folder to PATH.
