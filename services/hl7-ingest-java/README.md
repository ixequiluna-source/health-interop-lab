# `hl7-ingest` — Java 21

MLLP listener that parses HL7 v2 ADT messages, maps them onto a canonical admission event and
publishes them to Kafka.

## Why it is written this way

**No HL7 library.** The parser is the point of the exercise, and a hand-written one makes the
three classic failure modes explicit and testable:

| Trap | What goes wrong in the field | Where it is handled |
|---|---|---|
| MSH is off by one | MSH-1 *is* the field separator, so the header's token layout differs from every other segment. Split uniformly and MSH-10 reads as MSH-9 — the ACK then quotes the wrong control id and the sender never closes the message out. | `Hl7Parser.parseSegment` |
| MSH-2 is data | It contains `^~\&` — the delimiters themselves. Splitting it destroys the message's own description of its encoding. | `Hl7Parser.parseSegment` |
| Escapes resolve last | Delimiters inside data arrive escaped (`\F\`, `\S\`…). Decode before splitting and you re-inject delimiters into data, shifting every later field. | `Hl7Parser.unescape` |

**Nothing assumes `|^~\&`.** Delimiters are read from each message. `Hl7ParserTest` parses a
message that uses `#@~\&` and expects identical results.

**Trailing empty fields survive.** `String.split` drops them, which erases the difference
between "not sent" and "sent empty" — a real distinction in ADT updates.

**Partial dates are widened, not padded.** `19740314`, `197403` and `1974` map to
`1974-03-14`, `1974-03` and `1974`. Padding a partial date to January 1st invents a birthday,
and paediatric dosing downstream is computed from it.

**Timestamps keep the sender's offset.** An offset-less DTM stays offset-less rather than being
assumed UTC; assuming shifts every overnight admission onto the wrong day.

## Acknowledgement semantics

HL7 senders behave differently per code, so the three outcomes are kept distinct:

- **AA** — parsed, mapped, and durably published.
- **AE** — valid HL7 this service will not process (unsupported trigger, no patient identifier).
  Not retryable, but visible as an application error in the sender's monitoring.
- **AR** — not valid HL7 at all.
- **no ACK, connection dropped** — the event could not be published. Answering AA here would
  tell the sending system an admission is safe when it was never written; dropping the
  connection makes the sender retry.

`AdtMapper` accepts only `A01, A02, A03, A04, A08, A28, A31`. An interface that maps every ADT
trigger onto "admission" creates encounters for cancellations, and it surfaces months later as
a bed-occupancy report nobody can reconcile.

## Producer durability

`KafkaEventSink` pins `acks=all`, `enable.idempotence=true`, `max.in.flight=5` and infinite
retries under a delivery timeout. None of those are client defaults, and each has a named
failure mode — asserted in `KafkaEventSinkConfigTest` so a config tidy-up fails the build
instead of quietly downgrading delivery guarantees.

Events are keyed by patient MRN so an A01 admit and a later A08 update for the same patient
cannot be reordered across partitions.

## Run it

```bash
mvn -B verify                  # unit tests + 70% instruction coverage gate (JaCoCo)
mvn -B package                 # shaded jar at target/hl7-ingest.jar

# In-memory sink, no broker needed:
java -jar target/hl7-ingest.jar

# With Kafka:
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 java -jar target/hl7-ingest.jar
```

Send a message through MLLP:

```bash
printf '\x0bMSH|^~\\&|EPIC_ADT|HGS|LAB|FIRMUS|20260825143000||ADT^A01|MSG1|P|2.5.1\rPID|1||MRN-1||Luna^Ixequi\rPV1|1|I\x1c\r' \
  | nc localhost 2575
```

| Variable | Default | Meaning |
|---|---|---|
| `MLLP_PORT` | `2575` | MLLP listener port |
| `HTTP_PORT` | `8080` | `/healthz` and `/metrics` |
| `KAFKA_BOOTSTRAP_SERVERS` | *(empty)* | Empty runs an in-memory sink |
| `KAFKA_TOPIC` | `clinical.admissions.v1` | Destination topic |
| `MLLP_READ_TIMEOUT_MS` | `300000` | Bounds half-open sockets |

## Note on PHI

The service logs message control ids and event ids, never message bodies. HL7 payloads are PHI
and application logs are rarely in the same retention and access regime as the clinical store —
which is exactly the gap SOC 2 CC6.1 and the HIPAA minimum-necessary rule are about.
