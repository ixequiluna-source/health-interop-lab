# `patient-gateway` — Go

Read-only gRPC gateway over the patient projection: `GetPatient`, `SearchPatients` and a
server-streaming `StreamEncounters`.

## Why it is written this way

**The domain has no idea protobuf exists.** `internal/patient` is plain Go — structs,
validation, search, ordering, pagination — with no generated code and no database driver.
`internal/grpcapi` translates shapes and maps errors onto status codes; `internal/mongostore`
talks to MongoDB. The rules are therefore testable without a broker, a database or a
generated stub, and the wire format and storage engine stay replaceable details.

**Read-only by construction, and enforced at the network.** Writes enter the platform through
the HL7 feed, so every change to a patient record has an inbound clinical message behind it.
A second, unaudited write path is how two systems end up disagreeing about a patient with no
way to say which one is right. The `patient-gateway` NetworkPolicy has no egress to Kafka, and
a policy test asserts it stays that way — otherwise the claim is a convention, not a control.

**Cursor pagination, and the cursor does not carry the search term.** Page tokens travel in
query strings, proxy logs and browser history. A token is `v1:<offset>:<hash of the folded
term>`; the hash still detects a cursor being reused against a different query — returning the
wrong slice of a different result set — without putting a patient name somewhere it was never
classified to go.

**Page size is capped at 100.** That is a disclosure control, not a performance tweak. An
uncapped page size lets one request pull the entire patient index in a single response.

**Search folds diacritics.** `Núñez` and `Nunez` must find the same patient: names arrive from
one system accented and from another not, and an exact-match search reports "no such patient"
for someone who is currently admitted. Folding `ñ` to `n` is not linguistically neutral —
they are distinct letters in Spanish — and the comment in `fold.go` says so. Recall is the
safer failure here; the clinician sees both records and decides.

**Search ordering is explicit.** Go randomises map iteration, so an unsorted result makes
pagination silently repeat and omit patients once a result set outgrows one page.
`TestSearchOrderingIsStableAcrossRuns` runs the same search 25 times to catch it.

**The search term is escaped before it reaches a query pattern.** It arrives as part of a
regular expression, so an unescaped `(` is a malformed query and an unescaped `(a+)+$` is a
denial-of-service against the database. Escaping is not optional because the input is a name.

**No patient identity in span attributes.** Traces leave the audited boundary. `GetPatient`
records whether an identifier was present, never its value; `SearchPatients` records page size
and match count, never the term. The collector deletes those keys anyway as a backstop.

## Generated code

`gen/` is not committed. CI runs `make proto` so the stubs cannot drift from the `.proto` they
claim to describe. Regenerate locally with:

```bash
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
make build          # proto + tidy + build
make test           # go test -race ./...
```

`go.sum` is likewise absent and resolved by `go mod tidy` in CI, because the environment this
service was authored in had no access to the Go module proxy.

## Run it

```bash
go run .                                   # in-memory store, seeded, no dependencies
MONGODB_URI=mongodb://localhost:27017 go run .
```

| Variable | Default | Meaning |
|---|---|---|
| `GRPC_PORT` | `9090` | gRPC listener |
| `HTTP_PORT` | `8080` | `/healthz` |
| `MONGODB_URI` | *(empty)* | Empty serves an in-memory store with sample data |
| `MONGODB_DATABASE` | `interop` | Database holding the projection |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | *(empty)* | Empty installs a no-op tracer |
| `OTEL_TRACES_SAMPLER_ARG` | `0.1` | Head-sampling ratio for root spans |
| `GRPC_REFLECTION` | `false` | Reflection hands an unauthenticated caller the full schema |

## Shutdown

On SIGTERM the health service reports `NOT_SERVING` first, so load balancers stop sending new
work, and only then does the server drain — with a 20-second ceiling before a forced stop.
Draining without flipping health first means new requests keep arriving at a server that is
trying to finish.
