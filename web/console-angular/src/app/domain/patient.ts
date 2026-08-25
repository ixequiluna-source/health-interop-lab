/**
 * The read model this console renders.
 *
 * These types are a hand-written mirror of `proto/interop/v1/patient.proto`, not generated
 * code. The reason is that the console consumes the *proto3 JSON* projection of those
 * messages through a Connect proxy, and the generated TypeScript for a proto carries the
 * runtime (`Message` base classes, `fromBinary`, field descriptors) into a bundle that only
 * ever needs the shapes. Hand-mirroring costs a review step when the proto changes and buys
 * back a dependency-free domain layer that the in-memory gateway can construct directly.
 *
 * The rule that keeps the mirror honest: every field name here is the proto3 JSON name
 * (lowerCamelCase of the proto field), so a mismatch is a one-line diff against the .proto
 * rather than a translation exercise.
 */

/**
 * One assigning-authority-scoped identifier.
 *
 * `system` is the assigning authority (HGS, IMSS), not a URL — this is HL7 v2 CX semantics
 * carried forward, not FHIR's canonical-URI convention.
 */
export interface Identifier {
  /** Assigning authority, e.g. `HGS` or `IMSS`. */
  readonly system: string;
  readonly value: string;
  /** HL7 table 0203 identifier type, e.g. `MR` or `SS`. */
  readonly type: string;
}

export interface Patient {
  readonly medicalRecordNumber: string;
  readonly identifiers: readonly Identifier[];
  readonly familyName: string;
  readonly givenName: string;

  /**
   * ISO 8601, possibly partial: `1990`, `1990-03` and `1990-03-14` are all legal, because
   * HL7 v2 permits partial dates and the ingest service widens rather than pads them.
   *
   * This is a `string` and not a `Date` on purpose. Parsing it into a `Date` forces the
   * runtime to invent a month and a day for `1990`, and the invented day then flows into
   * any age calculation the UI does. Rendering the string the sender actually transmitted
   * is the only representation that does not fabricate clinical fact.
   */
  readonly birthDate: string;

  /**
   * HL7 table 0001 administrative sex (`M`, `F`, `O`, `U`, `A`, `N`).
   *
   * Administrative, not clinical: it is the value the registration system holds, and it is
   * not a statement about the patient's anatomy, gender identity or clinical sex. The UI
   * labels it "Administrative sex" in full for that reason.
   */
  readonly administrativeSex: string;

  /** RFC 3339 timestamp — proto3 JSON encodes `google.protobuf.Timestamp` as a string. */
  readonly lastUpdated: string;
}

/** A compound point of care: HL7 PV1-3 flattened. */
export interface Location {
  readonly pointOfCare: string;
  readonly room: string;
  readonly bed: string;
  readonly facility: string;
}

export interface Encounter {
  readonly visitNumber: string;
  readonly medicalRecordNumber: string;
  /** HL7 table 0004: `I` inpatient, `O` outpatient, `E` emergency, `P` preadmit… */
  readonly patientClass: string;
  /** RFC 3339. */
  readonly admittedAt: string;
  readonly attendingClinician: string;
  readonly location: Location;
}

/**
 * One page of search results.
 *
 * `nextPageToken` is an **opaque** cursor. The console never parses it, never infers a page
 * number from it, and never constructs one: the only legal thing to do with a token is hand
 * it back to the gateway unchanged. See `PatientGateway` for why the API is cursor-based
 * rather than offset-based.
 *
 * An absent `nextPageToken` — not an empty `patients` array — is the end-of-results signal.
 * A cursor API is allowed to return a short or even empty page and still have more results;
 * treating "fewer rows than I asked for" as the terminator is the classic way to silently
 * truncate a result set.
 */
export interface PatientPage {
  readonly patients: readonly Patient[];
  readonly nextPageToken: string | null;
  /**
   * Total matches across all pages.
   *
   * The server computes this because the console cannot: with cursor paging it has only
   * ever seen the pages it fetched. It is a count at the time the page was produced, so a
   * concurrent admission can make it disagree with the number of rows eventually walked —
   * which is why the UI phrases it as "N matched" rather than implying it is a live total.
   */
  readonly totalMatched: number;
}

/** Renders "Given Family", omitting whichever part is absent. */
export function fullName(patient: Patient): string {
  return `${patient.givenName} ${patient.familyName}`.trim();
}

/**
 * Diacritic-folding, case-insensitive normalisation for comparison.
 *
 * This mirrors `patient.Fold` in the Go gateway, and it mirrors it *deliberately*: the
 * in-memory gateway has to reproduce server-side matching closely enough that developing
 * against it does not teach the UI wrong assumptions about recall.
 *
 * Folding ñ to n is not linguistically neutral — in Spanish orthography they are distinct
 * letters. It is done because patient names arrive from one system with accents and from
 * another without, and a search that treats "Nunez" and "Núñez" as different people reports
 * "no such patient" for someone who is currently admitted. Over-recall is the safer failure.
 *
 * NFD decomposition plus a combining-mark strip is used rather than an explicit character
 * table because the JS engine already ships the Unicode data; the Go service writes the
 * table out by hand because pulling `golang.org/x/text` in for one function was the worse
 * trade there. Both produce the same result for the Latin-script name data in this system.
 */
export function fold(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '');
}
