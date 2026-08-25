# `claims-edi` — C# / .NET 8

The claims tail of the pipeline. It reads canonical admission events produced by
[`hl7-ingest`](../hl7-ingest-java/README.md), joined with charge detail, and emits X12 837P
(Professional Health Care Claim, 005010X222A1) interchanges onto an SQS FIFO queue.

## Why it is written this way

**No EDI library.** The encoder and the parser are the point of the exercise, and hand-writing them
makes the failure modes explicit and testable. Each of the four below is silent — the interchange
is syntactically plausible and semantically wrong, so nothing fails until a partner's translator or,
worse, a payer's adjudication engine acts on it.

| Trap | What goes wrong in the field | Where it is handled |
|---|---|---|
| ISA is fixed width | ISA is the only segment with fixed-width elements and must be exactly 106 characters. Pad the 15-character sender id to its actual length instead and the segment is 97 characters; the partner then reads offset 105 for the terminator, finds a digit of the control number, and rejects the interchange with a TA1 that says only "invalid interchange header". Nothing about the claims is wrong. | `X12IsaLayout`, `X12Writer.FitIsaElement` |
| Control numbers are linkages, not decoration | `SE02` must echo `ST02`, `GE02` must echo `GS06`, `IEA02` must echo `ISA13`. These are validated at the envelope, before a single claim is looked at, so one wrong digit rejects the whole file. | `X12Writer.Write`, `X12Reader.BuildInterchange` |
| `SE01` counts ST and SE themselves | Counting only the body, or the body plus ST, is the most common 837 rejection there is. The writer computes it from what it actually serialises, so adding a segment to the builder cannot desynchronise it. | `X12Writer.WriteTransactionSet` |
| Delimiters are data | Every interchange declares its own encoding inside its own ISA. Hard-code `*` and `~` and the parser works against every sample file on the internet, then fails on the first partner who uses `|` and a newline — silently, because splitting on a character the sender never used yields one enormous "segment". | `X12Delimiters`, `X12Reader.ReadDelimiters` |

**X12 has no escape mechanism.** Unlike HL7 v2, which carries a literal field separator as `\F\`,
there is no way to represent a delimiter inside an element value. A surname of `O*BRIEN` in a
star-delimited interchange shifts every later element in the segment, so the payer reads the total
charge out of the element that should have held the subscriber id. The interchange passes syntax
validation. The claim is wrong.

The default is therefore to **refuse** (`X12DelimiterPolicy.Reject`) with an error naming the
segment, the element and the offending character. A claim we cannot encode is a data-quality
problem upstream, and a loud error at the boundary is worth more than a submitted claim with a
quietly mangled name. `X12DelimiterPolicy.Strip` exists because some clearinghouse contracts
require best-effort submission of a whole batch — but stripping is then a decision someone made on
purpose, not a default.

**Composites are modelled, not concatenated.** An element is a list of components
(`X12Element`), so `CLM05` is `["21", "B", "1"]` rather than the string `"21:B:1"`. That is what
lets the writer forbid the component separator inside a *value* while still emitting it between
*components*, and it is what makes the same claim re-encode correctly for a partner whose ISA16 is
not a colon.

**Partial dates are refused, not padded.** The upstream service deliberately preserves `1974`,
`1974-03` and `1974-03-14` as three different statements about what the sender knew. X12 has no
partial date. Padding `1974-03` to `19740301` fabricates a birthday that the payer then matches
against eligibility, so `DMG` is omitted instead. Where the date is not optional — the date of
service — the claim is rejected with a message naming the field.

**Money is formatted invariantly and rounded away from zero.** `CultureInfo.InvariantCulture`
because a build host with a European locale would emit `125,50`, which no clearinghouse parses.
`MidpointRounding.AwayFromZero` because .NET's default is banker's rounding, and a cent of drift
between the line charges and `CLM02` is a hard payer edit — `CLM02` must equal the sum of the
`SV102` amounts exactly.

## The 837P this produces

Two hierarchical levels: a billing provider (`HL03 = 20`) with one subscriber child
(`HL03 = 22`) who is also the patient. When the subscriber is the patient, 5010 forbids the 2000C
patient loop, which is why the subscriber's `HL04` is `0` and not `1` — declaring children and not
sending them is a structural rejection.

```
ISA*00*          *00*          *ZZ*FIRMUSHEALTH   *ZZ*CLEARINGHOUSE  *260825*1430*^*00501*000000001*0*T*:~
GS*HC*FIRMUS01*CH0001*20260825*1430*1*X*005010X222A1~
ST*837*0001*005010X222A1~
BHT*0019*00*B000000001*20260825*1430*CH~
NM1*41*2*FIRMUS HEALTH GROUP*****46*FIRMUS01~     <- 1000A submitter
PER*IC*EDI OPERATIONS*TE*4045550100~
NM1*40*2*ACME CLEARINGHOUSE*****46*CH0001~        <- 1000B receiver
HL*1**20*1~                                       <- 2000A billing provider, no parent, has children
PRV*BI*PXC*207Q00000X~
NM1*85*2*FIRMUS HEALTH GROUP*****XX*1234567893~   <- 2010AA, XX = NPI
N3*1 HOSPITAL WAY~
N4*ATLANTA*GA*303011234~
REF*EI*581234567~
HL*2*1*22*0~                                      <- 2000B subscriber, parent 1, no children
SBR*P*18*******CI~                                <- primary, relationship 18 = self
NM1*IL*1*LUNA*IXEQUI****MI*MRN-4417~              <- 2010BA
DMG*D8*19740314*M~
NM1*PR*2*ACME HEALTH PLAN*****PI*60054~           <- 2010BB
CLM*CLM-1001*425.5***21:B:1*Y*A*Y*Y~              <- CLM05 composite: place of service ^ B ^ frequency
DTP*435*DT*202608251430~                          <- admission date, inpatient only
HI*ABK:J189~                                      <- ABK, not the 4010-era BK
LX*1~
SV1*HC:99223:25*425.5*UN*1***1~
DTP*472*D8*20260825~
SE*23*0001~                                       <- 21 body segments + ST + SE
GE*1*1~
IEA*1*000000001~
```

`ISA09` is `YYMMDD` and `GS04` is `CCYYMMDD`. The ISA was never widened to a four-digit year; that
is the standard, not a defect in this writer.

## Reading interchanges back

`X12Reader` is not a convenience. It validates more than it needs to in order to build the object
graph — every control-number linkage, every count — and the worker runs every interchange it writes
back through it before publishing. That costs microseconds and means a builder regression fails in
CI, on our side, instead of arriving as a 999 acknowledgement days later.

Failures are `X12ParseException` carrying an `X12ErrorCode` and the 1-based position of the
offending segment:

```
[SegmentCountMismatch] segment 31 (SE): SE01 declares 29 segments; the transaction set
contains 31 counting ST and SE themselves.
```

One deliberate asymmetry: `ST02`/`SE02` are compared ordinally because both are AN fields the
standard requires to be identical, but `ISA13`/`IEA02` and `GS06`/`GE02` are compared numerically.
`ISA13` is fixed width and zero filled to nine characters while `IEA02` is variable length and
commonly sent unpadded, so an ordinal comparison rejects `ISA13=000000001 / IEA02=1` — a valid
interchange. A false rejection there is more expensive than the mismatch the check exists to find.

## Why double billing is the failure mode that matters

Every other error in this service produces a rejection. A bad `SE01` bounces at the clearinghouse,
a bad diagnosis qualifier bounces at the payer, somebody fixes it. **A duplicate 837P does not
bounce — it adjudicates.** The payer pays the same claim twice, the duplicate surfaces months later
in an overpayment recovery, and for a government payer a pattern of duplicates is a False Claims
Act exposure rather than an accounting one. The asymmetry is total: losing a claim costs a
resubmission, duplicating one costs a compliance incident.

So the SQS `MessageDeduplicationId` is derived **only** from the claim id — SHA-256 of
`claim-837p:<CLM01>`. Not from the payload, not from a timestamp, not from a GUID. Anything that
varies between attempts (a rebuilt interchange with a new `ISA13`, a retry after a socket timeout
where the first send actually succeeded) would produce a different id and defeat the mechanism
exactly when it is needed.

Two honest limits, both asserted in tests:

- **SQS deduplicates within a five-minute window.** That covers retry storms and a pod restarting
  mid-batch. It does *not* cover resubmitting the same claim tomorrow, which needs a durable
  submitted-claims store keyed on the same claim id. This is one layer, not the whole defence.
- **The queue must be FIFO.** A standard queue accepts `MessageDeduplicationId` and silently
  ignores it. `SqsPublisher` refuses a queue URL that does not end in `.fifo` at construction,
  because that is the last point at which the mistake is cheap.

`MessageGroupId` is the patient's medical record number, mirroring the Kafka partition key
`hl7-ingest` uses. Same key across the pipeline means an original claim and its later replacement
(`CLM05-3 = 7`) cannot be reordered relative to one another, in the same way an A01 admit and its
A08 update cannot be.

## Interchange control numbers survive restarts

`ISA13` is deduplicated by trading partners over a retention window measured in months. The
counter is persisted (`FileControlNumberSequence`, write-then-rename) on a volume that must outlive
the container. The failure this exists to prevent: an ephemeral filesystem, a restart, the counter
begins at 1 again, and every interchange for the next several days is rejected as a duplicate of
one already received. The service looks healthy, the queue drains, and nothing is paid. A corrupt
counter file is fatal rather than silently reset, for the same reason.

## Run it

```bash
dotnet test                       # unit tests
dotnet build -c Release

# Build one interchange from a joined claim request and print it:
dotnet run --project ClaimsEdi -- build sample-claim.json

# Parse an interchange and validate its envelope:
dotnet run --project ClaimsEdi -- build sample-claim.json | dotnet run --project ClaimsEdi -- verify -

# Poll a directory; with no SQS_QUEUE_URL this runs a dry-run publisher and writes to the outbox:
EDI_INPUT_DIR=./inbox EDI_OUTPUT_DIR=./outbox EDI_FAILED_DIR=./failed \
EDI_CONTROL_NUMBER_FILE=./control-number \
  dotnet run --project ClaimsEdi -- serve
```

The input is one canonical admission event joined with the charge detail a claim needs and an
admission event cannot carry. An ADT message describes an admission; it says nothing about what was
done or what it cost. Rather than default a procedure code so the pipeline "works" end to end, the
service consumes the joined document and fails loudly when the charge detail is absent.

```json
{
  "event": {
    "schemaVersion": "1.0.0",
    "eventId": "evt-77",
    "recordedAt": "2026-08-25T14:30:05Z",
    "patient": {
      "medicalRecordNumber": "MRN-4417",
      "familyName": "LUNA",
      "givenName": "IXEQUI",
      "birthDate": "1974-03-14",
      "administrativeSex": "M"
    },
    "encounter": {
      "visitNumber": "V-90210",
      "patientClass": "I",
      "admitDateTime": "2026-08-25T14:30:00Z"
    }
  },
  "claimId": "CLM-1001",
  "principalDiagnosisCode": "J189",
  "serviceLines": [
    { "procedureCode": "99223", "modifiers": ["25"], "chargeAmount": 425.50, "units": 1 }
  ]
}
```

Absent fields are absent, not null — the Java producer omits them, and this consumer treats
"omitted" and "present but empty" as the same thing everywhere it can and as different things where
the distinction matters.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `EDI_INPUT_DIR` | `/var/lib/claims-edi/inbox` | Polled for `*.json` claim requests |
| `EDI_OUTPUT_DIR` | `/var/lib/claims-edi/outbox` | Published interchanges are archived here |
| `EDI_FAILED_DIR` | `/var/lib/claims-edi/failed` | Rejected inputs, with a `.error` file beside each |
| `EDI_CONTROL_NUMBER_FILE` | `/var/lib/claims-edi/control-number` | `ISA13` sequence. Must survive restarts |
| `EDI_POLL_INTERVAL_SECONDS` | `5` | Sleep when the inbox is empty |
| `HEALTH_PORT` | `8080` | Liveness endpoint, any path |
| `SQS_QUEUE_URL` | *(empty)* | Empty runs a dry-run publisher. Must end in `.fifo` |
| `AWS_REGION` | *(SDK default)* | Credentials come from the default provider chain |
| `X12_ELEMENT_SEPARATOR` | `*` | Per trading partner. `\n`, `\r`, `\t` accepted as escapes |
| `X12_COMPONENT_SEPARATOR` | `:` | `ISA16` |
| `X12_REPETITION_SEPARATOR` | `^` | `ISA11` |
| `X12_SEGMENT_TERMINATOR` | `~` | The one delimiter allowed to be a control character |
| `X12_DELIMITER_POLICY` | `reject` | `reject` or `strip` for values containing a delimiter |
| `X12_USAGE_INDICATOR` | `T` | `ISA15`. `T` test, `P` production — validated, never coerced |
| `X12_SENDER_QUALIFIER` / `X12_SENDER_ID` | `ZZ` / `FIRMUSHEALTH` | `ISA05` / `ISA06` |
| `X12_RECEIVER_QUALIFIER` / `X12_RECEIVER_ID` | `ZZ` / `CLEARINGHOUSE` | `ISA07` / `ISA08` |
| `SUBMITTER_NAME` / `SUBMITTER_ID` | `FIRMUS HEALTH GROUP` / `FIRMUS01` | Loop 1000A, and `GS02` |
| `SUBMITTER_CONTACT_NAME` / `SUBMITTER_CONTACT_PHONE` | `EDI OPERATIONS` / `4045550100` | `PER02` / `PER04` |
| `RECEIVER_NAME` / `RECEIVER_ETIN` | `ACME CLEARINGHOUSE` / `CH0001` | Loop 1000B, and `GS03` |
| `BILLING_PROVIDER_NAME` | `FIRMUS HEALTH GROUP` | Loop 2010AA `NM103` |
| `BILLING_PROVIDER_NPI` | `1234567893` | `NM109` with qualifier `XX` |
| `BILLING_PROVIDER_TAX_ID` | `581234567` | `REF*EI` |
| `BILLING_PROVIDER_TAXONOMY` | `207Q00000X` | `PRV03` with qualifier `PXC` |
| `BILLING_PROVIDER_ADDRESS` | `1 HOSPITAL WAY` | `N301`. A PO box here is a 5010 rejection |
| `BILLING_PROVIDER_CITY` / `_STATE` / `_POSTAL_CODE` | `ATLANTA` / `GA` / `303011234` | `N401`–`N403` |
| `PAYER_NAME` / `PAYER_ID` | `ACME HEALTH PLAN` / `60054` | Loop 2010BB |
| `PAYER_CLAIM_FILING_INDICATOR` | `CI` | `SBR09`. `CI` commercial, `MC` Medicaid, `MB` Medicare B |

`X12_USAGE_INDICATOR` is the single most consequential character in the file. A production
indicator on a test batch bills real money; a test indicator on a production batch means the claims
are silently discarded and nobody is paid. It is validated at start-up rather than defaulted
permissively.

## Health

`GET` anything on `HEALTH_PORT` returns `200 ok` while the poll loop is running and
`503 degraded` once it has faulted. It reports liveness, not readiness, and deliberately says
nothing about whether SQS is reachable: a health check that fails when a downstream dependency is
unavailable makes the orchestrator restart a process that was working perfectly, turning a
partner's outage into one of our own.

The container's `HEALTHCHECK` runs `dotnet ClaimsEdi.dll healthcheck`, which probes that endpoint
over loopback. The .NET runtime image ships with neither `curl` nor `wget`, and adding one adds a
package and its CVE stream to every deployment; the cost of the alternative is a short-lived
runtime process every thirty seconds.

## Note on PHI

An 837P is PHI. The service logs claim ids, control numbers and error codes, never element values
and never the interchange itself. Rejection detail — which can quote a patient's name back at you,
because that is often exactly what caused the rejection — is written next to the quarantined input
file, where it inherits that file's access controls, rather than into stdout. SQS message
attributes carry correlation identifiers only, because attributes surface in queue metrics,
dead-letter dumps and support tickets, which are not under the same access regime as the claim
store.

Same reasoning as `hl7-ingest`: application logs are rarely in the same retention and access regime
as the clinical store, which is the gap SOC 2 CC6.1 and the HIPAA minimum-necessary rule are about.
