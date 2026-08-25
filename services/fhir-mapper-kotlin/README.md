# `fhir-mapper` — Kotlin 2.1 / JVM 21

Consumes canonical admission events from Kafka, maps them onto FHIR R4 `Patient` and `Encounter`
resources, and maintains the denormalised read model the Go patient gateway serves.

```
hl7-ingest (Java)  ──▶  clinical.admissions.v1  ──▶  fhir-mapper (this)  ──▶  MongoDB
     MLLP / ADT              Kafka                    FHIR R4 mapping         patients
                                 │                                            encounters
                                 └────────────▶  clinical.admissions.v1.dlq        │
                                                    poison messages                ▼
                                                                        patient-gateway (Go)
```

It sits between two contracts it does not own. Upstream is
`ai.firmus.interop.hl7.AdmissionEvent` — the envelope shape, including the fact that the producer
*omits* empty fields rather than sending null. Downstream is `mongostore.patientDoc` and
`mongostore.encounterDoc` in the Go gateway, which decode by explicit `bson:` tag. Both are wire
contracts; the field names in `PatientProjection` and `EncounterProjection` are not a design choice
and renaming one is an outage, not a refactor.

## Why it is written this way

### The FHIR mapping is hand-written

No HAPI. The mapping surface is two resources and about a dozen elements, and all of the
interesting work is in decisions a structure library does not make for you: which HL7 code becomes
which FHIR code, what to do when a required element has no source value, and — most often — when
*not* to assert something.

One rule governs `FhirMapper`: **an element is emitted only when the source message actually said
it.** Absent, unknown and false are three different things.

| Situation | What is emitted | Why not the obvious alternative |
|---|---|---|
| PID-8 empty | no `gender` element | `gender: unknown` asserts someone asked and did not find out. Omission says the message did not carry it. One is a data-quality report, the other is a clinical statement. |
| PID-7 is `1974` | `birthDate: "1974"` | Padding to `1974-01-01` invents a birthday, and paediatric dosing downstream is computed from it. FHIR `date` has exactly three precisions and the upstream deliberately emits all of them. |
| PV1-2 blank or unrecognised | `class` = v3 NullFlavor `UNK` | `Encounter.class` is 1..1 in R4 so it cannot be omitted; defaulting to `AMB` asserts an outpatient visit nobody recorded. |
| PV1-44 absent | no `period` element | The read model needs *something* to sort on and falls back to the event's own `recordedAt`; that approximation stays in the read model and never reaches the resource. |
| PV1-7 absent | no `participant` | A display-only reference is used when a clinician *is* named, because no `Practitioner` resource exists to point at and fabricating one that cannot be de-duplicated is worse than a display string. |
| No PV1 at all (A28/A31) | no `Encounter` document | A visit with no number, no class and no admission time is a phantom admission that a bed-occupancy report cannot reconcile. |
| Nothing | `active` | An admission is evidence a record exists, not evidence about whether it is active — and `active: false` stops downstream systems accepting charting against it. |

**HL7 table 0001 → FHIR `administrative-gender`.** `M`→`male`, `F`→`female`, `O`→`other`,
`A` (ambiguous)→`other`, `U`→`unknown`, absent→omitted, unreadable→`unknown`.

`N` (not applicable) is mapped to `unknown`, which is a **deliberate deviation** from the R4
ConceptMap for this table, which maps it to `other`. `other` reads downstream as "a gender was
recorded and it was neither male nor female" — a clinical assertion. HL7's `N` means the concept
does not apply to this record at all. `unknown` is the weaker of the two and therefore the safer.
It is called out here so it is a decision rather than a bug.

**HL7 table 0004 → v3 ActCode.** `I`→`IMP`, `O`→`AMB`, `E`→`EMER`, `P`→`PRENC`, `B`→`IMP`,
`R`→`AMB`, everything else→`UNK`. `B` and `R` have no distinct ActCode; inventing one produces a
code no terminology server can resolve.

**Identifier systems differ between the two outputs, on purpose.** The gRPC contract documents
`Identifier.system` as a bare assigning authority ("e.g. HGS or IMSS"), so the read model carries
MSH-4. FHIR requires a URI, so the resource carries
`urn:firmus:identifier:<sanitised-authority>` — a facility name with a space is not a URI.

### Delivery is at-least-once, so the writes converge

Kafka is at-least-once and that is not an edge case: a pod rescheduled between a Mongo write and an
offset commit re-reads the same records on restart, every time. Two separate mechanisms handle two
separate problems, and conflating them is the usual mistake.

**Idempotency — the deterministic id.** `_id` is `sha256("Encounter|<mrn>|<visit>")`, not a
generated ObjectId. A replay upserts onto the document already there instead of inserting a second
one, so one admission cannot become three rows in the encounter list a clinician is reading. SHA-256
hex is 64 characters, exactly the FHIR `id` limit, and hashing rather than concatenating keeps the
MRN out of any URL that later ends up in a web-server access log. Where the sender gave no PV1-19
there is no visit identity to key on and the id falls back to the event id — weaker, because two
messages about one visit then produce two documents, but still idempotent under replay. Keying on
the admit timestamp instead would silently merge two different visits that began in the same second.

**Ordering — the staleness guard.** A deterministic id does *not* stop an older event overwriting
newer data: a replayed A01 admit and the A08 that corrected it target the same document, and
whichever lands last wins. So every upsert is filtered on `lastUpdated: {$not: {$gt: recordedAt}}`
— "not newer than the event in hand", which also matches a document that has no `lastUpdated` at
all, where a plain `$lte` would be false forever. Older events
genuinely arrive — an operator replaying an offset after a bad deploy, a consumer-group reset, a
second mapper instance started against the same data — and without the guard, the correction a
nurse made this morning is reverted by a message from Tuesday with no error anywhere.

The comparison is `<=`, not `<`. A tie means two events carry the same second for the same patient;
on a topic partitioned by MRN they were delivered in order, so the later arrival should win.
Rejecting ties would drop the second of two corrections made in the same second, invisibly. Letting
one through cannot duplicate anything, because the write still targets the same `_id`.

There is one subtlety worth knowing before editing `MongoWriter`: an upsert whose filter matches
nothing inserts a document built from the filter's equality clauses. When the guard rejects a write,
the `_id` clause still matches but the `lastUpdated` clause does not, so the server attempts an
insert with that `_id` and raises E11000. A duplicate key on that write therefore means exactly one
thing — stored data is newer — and it is swallowed rather than retried. That is why the catch is
there and why narrowing it to "any duplicate key is fine" elsewhere would be wrong.

No multi-document transaction wraps the two collections. It would buy less than it appears to: the
Kafka commit sits outside any Mongo transaction, so the pipeline is at-least-once end to end
regardless and still has to converge under replay — which it does. The write order is chosen so the
intermediate state is benign: the patient goes first because the gateway's `Encounters` call looks
the patient up before listing visits, so an encounter without its patient is invisible and reads as
data loss, whereas a patient with no encounters yet is just someone who has been registered.

### Offsets are committed manually, after the write

`enable.auto.commit=false`. Auto-commit runs on a timer inside `poll()` and can commit offsets for
records that have been *fetched* and not yet processed; a crash in that window loses those
admissions permanently, with zero lag and a healthy-looking consumer. Committing after the write
inverts the failure into a replay, which costs nothing here.

The committed offset is `record.offset() + 1`. Omit the `+1` and every restart reprocesses one
record per partition forever; apply it twice and every restart skips one admission.

Rebalances are handled explicitly. `onPartitionsRevoked` commits the work already done before the
partitions move — the one place where committing from a callback is correct. `onPartitionsLost`
deliberately does **not** commit: by then another consumer owns those partitions and may have
advanced past these offsets, so committing would move their position backwards.

`CooperativeStickyAssignor` is set because with eager assignment a rolling restart of *N* replicas
stops the entire group *N* times.

### Poison messages go to a dead-letter topic

One malformed message is a choice between two bad outcomes: retry forever and the partition stops,
so every patient whose MRN hashes to it silently stops being updated; or skip it and one admission
is missing. This service skips — and that is only defensible with three conditions attached:

1. **Only deterministic failures are parked.** A parse error or a validation error fails identically
   on every attempt. A Mongo timeout does not, and is retried and then replayed instead.
   Dead-lettering a transient failure turns a fifteen-minute database failover into silent,
   permanent loss of every admission that arrived during it. `ProjectionWriteException.retryable` is
   where that decision is made, and it is made by the layer that knows the answer.
2. **The park is durable before the offset advances.** `park()` blocks on the broker ack; if it
   fails, the offset does not move and the record replays.
3. **Someone watches `admissions_dead_lettered_total`.** A dead-letter topic with no alert on it is a
   way of deleting messages slowly.

The original key and value are republished byte-for-byte with the diagnosis in headers
(`dlq.reason`, `dlq.detail`, `dlq.source.{topic,partition,offset}`, `dlq.parked.at`). Replay after a
fix is then a console-consumer piped into a producer, not an unwrapping script somebody has to write
under pressure.

Transient write failures are retried *inside* the poll cycle, with a budget that is validated at
startup against `max.poll.interval.ms`. That check is not decoration: retries that outlast the
interval get the consumer evicted, which triggers a rebalance, which makes every consumer redo its
batch against the same slow database. It looks like a Kafka problem and it is timeout arithmetic.

### Logs never contain PHI

`Logger` takes an event name and typed fields, not a format string, because an API that takes a
message invites `log.info("mapped $event")` and that one line ships names and MRNs into a search
index. Call sites pass only identifiers that are meaningless without the clinical store: event ids,
HL7 message control ids, topic/partition/offset, counts, durations.

Two further defences, because the discipline is not enough on its own:

- **`toString` is overridden** on `AdmissionEvent`, both nested records, and both projections. A
  generated data-class `toString` renders every field, so one careless interpolation — or one
  exception message that includes the object — is a disclosure. `ProjectionTest` and
  `AdmissionEventTest` assert this.
- **Third-party exception messages are suppressed.** A JSON parser quotes the input it choked on; a
  driver quotes the document it rejected. Only exceptions this service authored — whose messages
  name fields, never values — have their message logged. Everything else contributes its type and
  the top stack frame in `ai.firmus.*`, which is what you actually need to find the code.
  `LOG_EXTERNAL_ERROR_MESSAGES=true` turns the rest on for a non-PHI environment.

This is what SOC 2 CC6.1 and the HIPAA minimum-necessary rule are about in practice: application
logs almost never live under the clinical store's retention, encryption and access-review regime.

### Shutdown

SIGTERM arrives as a JVM shutdown hook, which cannot touch the consumer directly — `KafkaConsumer`
is not thread-safe and `wakeup()` is the only method safe to call from another thread. The hook sets
the stop flag, wakes the poll, and then *waits*: returning immediately would let the JVM exit
mid-commit and, worse, skip the LeaveGroup, so the group would stall for a full session timeout on
every deploy. `close(Duration)` bounds the wait so an unresponsive broker cannot hold the pod past
its termination grace period, at which point the kill is a SIGKILL and nothing else gets to run.

## Configuration

All environment variables, all optional.

| Variable | Default | Meaning |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker list |
| `KAFKA_TOPIC` | `clinical.admissions.v1` | Source topic |
| `KAFKA_DLQ_TOPIC` | `clinical.admissions.v1.dlq` | Poison-message topic; must differ from the source |
| `KAFKA_GROUP_ID` | `fhir-mapper` | Consumer group |
| `KAFKA_CLIENT_ID` | `fhir-mapper-$HOSTNAME` | Per-replica id, so broker-side lag metrics can name the slow replica |
| `KAFKA_AUTO_OFFSET_RESET` | `earliest` | Only consulted with no committed offset; `latest` would skip pre-deployment history |
| `KAFKA_MAX_POLL_RECORDS` | `25` | Batch size; bounds the worst case between two `poll()` calls |
| `KAFKA_MAX_POLL_INTERVAL_MS` | `300000` | Eviction threshold the retry budget is validated against |
| `KAFKA_POLL_TIMEOUT_MS` | `1000` | Blocking poll timeout |
| `MONGODB_URI` | `mongodb://localhost:27017` | Connection string; redacted from `Config.toString` |
| `MONGODB_DATABASE` | `interop` | Database holding `patients` and `encounters` |
| `FACILITY_TIMEZONE` | `UTC` | Applied to timestamps the sender left offset-less. FHIR forbids a time of day without a zone, and assuming UTC moves overnight admissions onto the wrong day |
| `FHIR_IDENTIFIER_SYSTEM_BASE` | `urn:firmus:identifier` | URI prefix for `identifier.system` |
| `DEFAULT_ASSIGNING_AUTHORITY` | `UNKNOWN` | Used when MSH-4 is absent |
| `WRITE_RETRY_ATTEMPTS` | `4` | Attempts per record for a transient storage failure |
| `WRITE_RETRY_BASE_BACKOFF_MS` | `200` | First backoff; doubles per attempt |
| `WRITE_RETRY_MAX_BACKOFF_MS` | `2000` | Backoff ceiling |
| `SHUTDOWN_TIMEOUT_MS` | `20000` | Bound on the graceful stop |
| `HTTP_PORT` | `8081` | `/healthz`, `/readyz`, `/metrics` |
| `LOG_LEVEL` | `INFO` | `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `LOG_EXTERNAL_ERROR_MESSAGES` | `false` | Include third-party exception messages. **Only in a non-PHI environment** |

Startup fails with exit code 78 (`EX_CONFIG`) rather than starting on a configuration that cannot be
correct: a DLQ topic equal to the source topic, an unknown timezone, a retry budget that could
outlast the poll interval.

## Endpoints

| Path | Meaning |
|---|---|
| `/healthz` | Liveness. Fails only when the consume loop has stopped, which a restart fixes |
| `/readyz` | Readiness. Also fails when no `poll()` has completed within twice `KAFKA_MAX_POLL_INTERVAL_MS` — usually a broker problem, which restarting every replica makes worse |
| `/metrics` | Prometheus text. Alert on `admissions_dead_lettered_total`; watch `projections_stale_skipped_total`, which rising steadily means events are arriving out of order |

## Run it

```bash
gradle test                     # unit tests
gradle installDist              # build/install/fhir-mapper/bin/fhir-mapper

KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
MONGODB_URI=mongodb://localhost:27017 \
FACILITY_TIMEZONE=America/Mexico_City \
  ./build/install/fhir-mapper/bin/fhir-mapper
```

There is no Gradle wrapper in the repository. CI provisions Gradle with
`gradle/actions/setup-gradle`, and the Docker build uses the `gradle:8.12-jdk21` image.

Inspect what was written:

```bash
mongosh interop --eval 'db.patients.findOne({}, {searchTerms:1, foldedFamilyName:1, lastUpdated:1})'
mongosh interop --eval 'db.encounters.find().sort({admittedAt:-1}).limit(5)'
```

Read the dead-letter topic with its diagnosis headers:

```bash
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic clinical.admissions.v1.dlq --from-beginning --property print.headers=true
```

## Tests

`gradle test`. The mapping, folding, time handling and configuration are covered directly. Three
areas are worth calling out because the tests are doing something less obvious than assertion:

- **Folding is tested against the same four cases as the Go gateway** (`Núñez`→`nunez`,
  `José`→`jose`, `Müller`→`muller`, `Gonçalves`→`goncalves`). The two implementations are one rule
  in two languages; if they ever disagree, patient search silently stops finding people with accents
  in their names, and nothing reports an error.
- **The staleness guard is tested twice.** `isStale` is a pure predicate and is tested as one.
  `MongoWriter.stalenessGuardFilter` is the same rule expressed as BSON for the server, which cannot
  run Kotlin, and its exact shape is pinned — including the choice of `$not: {$gt: …}` over `$lte`,
  without which a document written before the field existed could never be updated again, and the
  requirement that `_id` stays the only top-level equality clause, which is what makes the
  duplicate-key collision a reliable staleness signal.
- **The consumer is tested with `MockConsumer`** for the things that only the loop can get wrong:
  that the committed offset is `last + 1`, that a poison message is parked *and* the partition keeps
  moving, that a message which could not be parked does not advance, and that a transient write
  failure commits nothing and dead-letters nothing.

There is no Testcontainers suite here. The behaviour that needs a real broker and a real replica set
— failover, rollback, index contention — is exercised in the repository's integration job against
the whole pipeline, not per-service.
