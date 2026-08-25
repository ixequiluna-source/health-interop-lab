# health-interop-lab

A polyglot, event-driven clinical interoperability platform. One pipeline, five languages,
each doing the job it is actually good at — plus the infrastructure, observability and
compliance controls that a system holding protected health information needs in order to run.

This exists as a working reference implementation rather than a set of exercises. Every claim
below is backed by code, tests and a CI job.

---

## The pipeline

```
Hospital interface engine
        │  HL7 v2 ADT^A01 over MLLP (TCP 2575)
        ▼
┌─────────────────────┐
│  hl7-ingest         │  Java 21 · hand-written ER7 parser · MLLP framing · ACK semantics
└──────────┬──────────┘
           │  canonical AdmissionEvent (JSON), keyed by patient MRN
           ▼
      ╔═════════╗
      ║  Kafka  ║  acks=all · idempotent producer · ordering per patient
      ╚════╤════╝
           ▼
┌─────────────────────┐
│  fhir-mapper        │  Kotlin · HL7 v2 → FHIR R4 · staleness guard · dead-letter sink
└──────────┬──────────┘
           │  read projection
           ▼
      ╔═════════╗
      ║ MongoDB ║  patients + encounters, indexed for the queries below
      ╚════╤════╝
           ▼
┌─────────────────────┐          ┌─────────────────────┐
│  patient-gateway    │  gRPC    │  claims-edi         │  C# · EDI X12 837P → SQS FIFO
│  Go · read-only     │◄─────────┤  .NET 8             │
└──────────┬──────────┘          └─────────────────────┘
           ▼
┌─────────────────────┐
│  console            │  Angular · signals · cursor pagination · OnPush
└─────────────────────┘

           every service ──── OTLP ───▶ OpenTelemetry Collector ──▶ backend
                                        (PHI redaction, tail sampling)
```

## What each part demonstrates

| Component | Stack | The substance |
|---|---|---|
| [`services/hl7-ingest-java`](services/hl7-ingest-java) | **Java 21**, Maven, JUnit 5, Kafka | A real HL7 v2 ER7 parser — MSH off-by-one, MSH-2 as data, escape sequences resolved last, non-default delimiters, trailing empty fields preserved. MLLP framing. AA/AE/AR acknowledgement semantics and a deliberate no-ACK path. |
| [`services/fhir-mapper-kotlin`](services/fhir-mapper-kotlin) | **Kotlin**, Gradle, Kafka, MongoDB | Consumes the canonical event, maps it onto FHIR R4 Patient and Encounter, writes the read projection, and refuses to apply a stale update. Dead-letter sink for poison messages. |
| [`services/patient-gateway-go`](services/patient-gateway-go) | **Go**, **gRPC**, **protobuf**, **OpenTelemetry**, **MongoDB** | Read-only query API. Domain logic with zero generated-code or driver imports. Cursor pagination that does not leak the search term. Diacritic-folding search. Regex escaping on user input. |
| [`services/claims-edi-dotnet`](services/claims-edi-dotnet) | **C# / .NET 8**, xUnit, **SQS** | Hand-written **EDI X12** reader and writer. Fixed-width ISA, computed SE/GE/IEA counts, control-number linkage, delimiters read from the interchange. FIFO queue with deterministic deduplication so a retry cannot double-bill. |
| [`web/console-angular`](web/console-angular) | **Angular**, TypeScript strict | Standalone components, signals, new control flow, OnPush, typed reactive forms, a trace-header interceptor, and a stale-response guard proven by test. |
| [`infra/terraform`](infra/terraform) | **Terraform**, **GCP**, **AWS** | GKE with private nodes, Workload Identity, Calico, etcd envelope encryption and Binary Authorization. Locked six-year audit retention. FIFO claims queue with a TLS-only policy. |
| [`infra/k8s`](infra/k8s) | **Kubernetes**, Kustomize | Restricted Pod Security Admission, default-deny NetworkPolicy, PDBs, resource quotas, digest pinning in the prod overlay. |
| [`observability`](observability) | **OpenTelemetry Collector** | PHI redaction processors, tail sampling that keeps every error and every slow trace, bounded receiver. |
| [`compliance/soc2`](compliance/soc2) | **SOC 2** controls as code | A control matrix where every row names the artifact and the test that fails the build when it regresses. |

## The tests are the point

```
Java        129 assertions verified across the parser, mapper, ACK builder and MLLP codec
Go          domain tests including a 25-iteration determinism check on search ordering
Angular     134 tests, 11 files, vitest + jsdom
Policy      68 SOC 2 control checks, ~2 seconds
Kotlin      mapper, staleness, projection, config and consumer tests
C#          ISA layout, SE counting, control-number linkage, round-trip, dedup
```

Every language has its own CI workflow in [`.github/workflows`](.github/workflows).

## Design decisions worth arguing about

Each of these is a place where the obvious implementation is wrong in a way that only shows up
in production. They are documented at the call site, not just here.

**MSH is off by one.** MSH-1 *is* the field separator, so the header's token layout differs
from every other segment. Parsers that split uniformly report MSH-10 as MSH-9, and the
acknowledgement then quotes the wrong control id — so the sending system never closes the
message out and resends it indefinitely.

**An ACK is a promise about durability.** Returning `AA` before the event is durably published
tells the hospital an admission is safe when it was never written. This service withholds the
acknowledgement and drops the connection instead, so the sender retries.

**`requests` and `limits` are different controls.** No limit lets a pod starve its neighbours.
No request gives the pod BestEffort QoS, making it the first thing evicted under memory
pressure — for the MLLP listener, that means dropping a live clinical feed exactly when the
node is busiest.

**At-least-once delivery means billing a patient twice.** Duplicate claims adjudicate rather
than bounce, and the recovery is a recoupment plus, in the United States, False Claims Act
exposure. Hence a FIFO queue and a deduplication id derived deterministically from the claim.

**Hashing an identifier is not de-identification.** A hashed MRN is a stable per-patient key
that re-identifies anyone who can correlate it with a second dataset. Page tokens carry a hash
of the *search term* (enough to detect cursor reuse) and the collector *deletes* patient
attributes rather than hashing them.

**NetworkPolicy without an enforcer is inert YAML.** It reads exactly like segmentation and
does nothing. That is why `network_policy { enabled = true }` is not optional in the Terraform,
and why a policy test asserts the read gateway has no egress path to Kafka.

**Partial dates are legal in HL7.** `1974`, `197403` and `19740314` all occur. Padding a
partial date to January 1st invents a birthday, and paediatric dosing downstream is computed
from it. The mapper widens instead of guessing.

## Running it

Each service runs standalone with no external dependencies — the ingest falls back to an
in-memory sink, the gateway to a seeded in-memory store, and the console to an in-memory
gateway that implements the same pagination contract.

```bash
# Java: MLLP listener on 2575, health on 8080
cd services/hl7-ingest-java && mvn -B verify && java -jar target/hl7-ingest.jar

# Go: gRPC on 9090
cd services/patient-gateway-go && make build && go run .

# Angular: http://localhost:4200
cd web/console-angular && npm ci && npm start

# Policy gate
python -m pytest tools/policy/test_policies.py -v
```

Send a message through the pipeline:

```bash
printf '\x0bMSH|^~\\&|EPIC_ADT|HGS|LAB|FIRMUS|20260825143000||ADT^A01|MSG1|P|2.5.1\rPID|1||MRN-1||Luna^Ixequi\rPV1|1|I\x1c\r' \
  | nc localhost 2575
```

## Scope and honesty

The compliance material demonstrates that security and availability controls can be expressed
as code and enforced by CI. It is **not** a SOC 2 report: a real attestation covers a service
organization's operating effectiveness over a period, assessed by an independent auditor, and
most of its scope is organizational rather than technical. Nothing here is a claim of
certification. The same applies to HIPAA references — they name the requirement a control is
aimed at, not a compliance status.

Sample data is synthetic. No real patient data appears anywhere in this repository.

---

Built by [Dr. Ixequi Luna](https://ixequiluna.ai) — physician, AI architect, and the person
who wrote every line here.
