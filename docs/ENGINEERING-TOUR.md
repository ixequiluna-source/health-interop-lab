# A focused tour of the engineering

[Back to the project](../README.md)

Read the failure case first, then the implementation and its tests.

1. **Header parsing:** inspect [Hl7Parser](../services/hl7-ingest-java/src/main/java/ai/firmus/interop/hl7/Hl7Parser.java) and its [tests](../services/hl7-ingest-java/src/test/java/ai/firmus/interop/hl7/Hl7ParserTest.java). MSH has a special field layout; splitting every segment identically loses meaning.
2. **Out-of-order events:** inspect [AdmissionProcessor](../services/fhir-mapper-kotlin/src/main/kotlin/ai/firmus/interop/fhir/AdmissionProcessor.kt) and [StalenessTest](../services/fhir-mapper-kotlin/src/test/kotlin/ai/firmus/interop/fhir/StalenessTest.kt). A late update must not silently overwrite a newer projection.
3. **Stable pagination:** inspect [patient tests](../services/patient-gateway-go/internal/patient/patient_test.go). Ordering and cursor behavior matter when results span multiple pages.
4. **Envelope integrity:** inspect [X12WriterTests](../services/claims-edi-dotnet/ClaimsEdi.Tests/X12WriterTests.cs) and [round-trip tests](../services/claims-edi-dotnet/ClaimsEdi.Tests/X12RoundTripTests.cs). Counts and linked control numbers must agree.
5. **UI races:** inspect [patient-search-store tests](../web/console-angular/src/app/features/patient-search/patient-search-store.spec.ts). An older search result must not replace the user's newer search.
6. **Controls as code:** inspect [policy tests](../tools/policy/test_policies.py) alongside the [control matrix](../compliance/soc2/control-matrix.md). Separate declared configuration from demonstrated runtime enforcement.

For a useful issue, include the affected contract, a minimal synthetic fixture, expected and actual behavior, and a failing test when possible. Never include patient records or credentials.
