# Architecture and integration boundaries

[Back to the project](../README.md)

## Implemented surfaces

| Boundary | Source | Contract |
| --- | --- | --- |
| HL7 → canonical event | [IngestHandler](../services/hl7-ingest-java/src/main/java/ai/firmus/interop/hl7/IngestHandler.java) | Parses and maps inbound messages; acknowledgement depends on processing outcome. |
| Event → projection | [AdmissionProcessor](../services/fhir-mapper-kotlin/src/main/kotlin/ai/firmus/interop/fhir/AdmissionProcessor.kt) | Processes admissions and updates projections through the store abstraction. |
| Projection → queries | [Patient proto](../proto/interop/v1/patient.proto) | GetPatient, SearchPatients and server-streaming StreamEncounters. |
| Console → local demo | [Development environment](../web/console-angular/src/environments/environment.ts) | In-memory gateway with synthetic fixtures. |
| Console → HTTP | [HTTP adapter](../web/console-angular/src/app/data/http-patient-gateway.ts) | Assumes unary JSON endpoints, including ListEncounters and pipeline status. |
| Files → X12 → SQS | [Claims entry point](../services/claims-edi-dotnet/ClaimsEdi/Program.cs) | Batch worker reads admission/charge files; build and verify commands also work on files. |

## Connections not established by this repository

The Go entry point serves gRPC. The browser client expects an HTTP/Connect-compatible adapter, including a unary encounter-list operation and operations status endpoint. Those requirements must be implemented and tested before calling the production console a connected application.

The claims worker receives files rather than calling the Go service. Upstream enrichment with charges and delivery into its input directory must be specified separately.

Compose, Kubernetes and Terraform describe environments. Their presence and static validation do not demonstrate a completed cloud deployment or full end-to-end message delivery. No such deployment was performed during the presentation review.

## Next integration milestone

One synthetic admission should produce an acknowledged message, a persisted projection, a queryable patient and a browser detail view through real transports. Record the input, correlation identifier, expected output and failure behavior. Add a separate claims fixture with explicit charge provenance; do not infer charges from admission data.
