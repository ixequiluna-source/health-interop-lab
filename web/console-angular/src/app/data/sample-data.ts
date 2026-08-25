import type { Encounter, Identifier, Patient } from '../domain/patient';
import type { PipelineStatus } from '../domain/pipeline';

/**
 * Seed data for `InMemoryPatientGateway`.
 *
 * Synthetic throughout — the names are common Mexican and Spanish surnames, the MRNs and NSS
 * numbers are made up, and nothing here corresponds to a person. It is written to be
 * *realistic* rather than convenient, because a fixture that is too tidy hides bugs:
 *
 * - There are more than one page worth of "García" and "Hernández" records, so cursor
 *   pagination is exercised by the most obvious thing an operator types.
 * - Surnames collide heavily, so the tie-break in the sort order (family, given, MRN) is
 *   load-bearing rather than decorative. A fixture of forty distinct surnames would let a
 *   broken tie-break ship.
 * - Accents are inconsistent between records on purpose — `Nunez` and `Núñez` both appear —
 *   because that is what the real feed looks like when one hospital's registration system
 *   strips diacritics and another's does not.
 * - Birth dates include partial values (`1978`, `1991-06`), because HL7 permits them and the
 *   UI has to render them without inventing a day.
 * - Some patients have no encounters, so the detail page's empty branch is reachable.
 */

/**
 * Patients as a compact tuple table rather than forty object literals.
 *
 * Not brevity for its own sake: in a table, the *shape* of the data is visible — you can see
 * at a glance that surnames repeat and that some birth dates are partial, which is the whole
 * reason this fixture exists. Forty literals bury that under punctuation.
 *
 * `[mrn, family, given, birthDate, sex, nss | null, daysSinceUpdate]`
 */
type PatientRow = readonly [string, string, string, string, string, string | null, number];

const PATIENT_ROWS: readonly PatientRow[] = [
  ['MRN-88213', 'Luna', 'Ixequi', '1990-03-14', 'M', 'NSS-4471120', 0],
  ['MRN-10042', 'García', 'María Fernanda', '1984-11-02', 'F', 'NSS-1120044', 0],
  ['MRN-10043', 'García', 'José Antonio', '1971-05-19', 'M', 'NSS-1120045', 1],
  ['MRN-10044', 'García', 'Ana Lucía', '1996-08-30', 'F', null, 1],
  ['MRN-10045', 'Garcia', 'Roberto', '1962-02-08', 'M', 'NSS-1120047', 2],
  ['MRN-10046', 'García', 'Sofía', '2001-12-25', 'F', 'NSS-1120048', 2],
  ['MRN-10047', 'García', 'Miguel Ángel', '1978', 'M', null, 3],
  ['MRN-10048', 'García', 'Carmen', '1955-07-11', 'F', 'NSS-1120050', 3],
  ['MRN-10049', 'García', 'Luis Enrique', '1989-01-23', 'M', 'NSS-1120051', 4],
  ['MRN-10050', 'García', 'Patricia', '1993-09-05', 'F', null, 4],
  ['MRN-10051', 'García', 'Fernando', '1966-04-17', 'M', 'NSS-1120053', 5],
  ['MRN-10052', 'García', 'Rosa María', '1948-10-29', 'F', 'NSS-1120054', 6],
  ['MRN-10053', 'García', 'Diego', '2015-03-03', 'M', null, 6],
  ['MRN-10054', 'García', 'Valentina', '2019-06-14', 'F', 'NSS-1120056', 7],
  ['MRN-10055', 'García', 'Alejandro', '1982-08-21', 'M', 'NSS-1120057', 7],
  ['MRN-10056', 'García', 'Beatriz', '1974-12-09', 'F', null, 8],
  ['MRN-10057', 'García', 'Ricardo', '1959-05-30', 'M', 'NSS-1120059', 9],
  ['MRN-10058', 'García', 'Elena', '1991-06', 'F', 'NSS-1120060', 9],
  ['MRN-10059', 'García', 'Javier', '1987-02-14', 'M', null, 10],
  ['MRN-10060', 'García', 'Isabel', '1969-11-27', 'F', 'NSS-1120062', 11],
  ['MRN-10061', 'García Méndez', 'Tomás', '1998-04-06', 'M', 'NSS-1120063', 11],
  ['MRN-10062', 'García Ruiz', 'Adriana', '1976-09-18', 'F', null, 12],
  ['MRN-10063', 'García Soto', 'Emiliano', '2008-01-31', 'M', 'NSS-1120065', 12],
  ['MRN-10064', 'García Vega', 'Lucía', '1963-07-22', 'F', 'NSS-1120066', 13],
  ['MRN-10065', 'García Ponce', 'Rafael', '1994-10-10', 'M', null, 14],
  ['MRN-10066', 'García Nava', 'Mariana', '1985-03-27', 'F', 'NSS-1120068', 14],
  ['MRN-10067', 'García Ibarra', 'Sergio', '1952', 'M', 'NSS-1120069', 15],
  ['MRN-20101', 'Hernández', 'Guadalupe', '1970-06-12', 'F', 'NSS-2210101', 0],
  ['MRN-20102', 'Hernandez', 'Óscar', '1988-03-08', 'M', 'NSS-2210102', 1],
  ['MRN-20103', 'Hernández', 'Silvia', '1997-11-19', 'F', null, 2],
  ['MRN-20104', 'Hernández', 'Arturo', '1961-01-04', 'M', 'NSS-2210104', 3],
  ['MRN-20105', 'Hernández', 'Verónica', '1979-08-16', 'F', 'NSS-2210105', 4],
  ['MRN-30201', 'Núñez', 'Claudia', '1983-05-21', 'F', 'NSS-3320201', 0],
  ['MRN-30202', 'Nunez', 'Ernesto', '1965-09-09', 'M', 'NSS-3320202', 2],
  ['MRN-30203', 'Núñez Ortiz', 'Paola', '2003-02-28', 'F', null, 5],
  ['MRN-40301', 'Ramírez', 'Jorge', '1958-04-25', 'M', 'NSS-4430301', 1],
  ['MRN-40302', 'Ramírez', 'Alma Delia', '1992-07-07', 'F', 'NSS-4430302', 3],
  ['MRN-40303', 'Ramírez Cruz', 'Andrés', '2011-10-13', 'M', null, 6],
  ['MRN-50401', 'Torres', 'Enrique', '1975-12-01', 'M', 'NSS-5540401', 0],
  ['MRN-50402', 'Torres', 'Gabriela', '1999-03-17', 'F', 'NSS-5540402', 4],
  ['MRN-60501', 'Vázquez', 'Ignacio', '1949-08-05', 'M', 'NSS-6650501', 8],
  ['MRN-60502', 'Vazquez', 'Renata', '2017-05-23', 'F', null, 8],
  ['MRN-70601', 'Ortega', 'Mónica', '1986-01-11', 'F', 'NSS-7760601', 2],
  ['MRN-70602', 'Ortega Salas', 'Héctor', '1972-06-29', 'M', 'NSS-7760602', 10],
  ['MRN-80701', 'Domínguez', 'Cecilia', '1990-10-02', 'F', null, 1],
  ['MRN-80702', 'Domínguez', 'Raúl', '1957-02-20', 'M', 'NSS-8870702', 13],
  ['MRN-90801', 'Fuentes', 'Ximena', '2006-09-15', 'F', 'NSS-9980801', 5],
  ['MRN-90802', 'Fuentes Aguilar', 'Bruno', '1981-11-26', 'M', null, 7],
];

/**
 * A fixed instant the fixture is generated relative to.
 *
 * The seed is built once at module load and `lastUpdated` is derived from *this* constant
 * rather than `Date.now()` at call time, so two renders of the same list never disagree and
 * a snapshot assertion in a test cannot fail because a second elapsed mid-suite. The console
 * still renders relative ages honestly; they are just anchored.
 */
const SEED_EPOCH = Date.UTC(2026, 7, 25, 14, 30, 0);

const DAY_MS = 24 * 60 * 60 * 1000;

function daysBefore(days: number): string {
  return new Date(SEED_EPOCH - days * DAY_MS).toISOString();
}

function identifiersFor(mrn: string, nss: string | null): readonly Identifier[] {
  const identifiers: Identifier[] = [{ system: 'HGS', value: mrn, type: 'MR' }];
  if (nss !== null) {
    identifiers.push({ system: 'IMSS', value: nss, type: 'SS' });
  }
  return identifiers;
}

export const SAMPLE_PATIENTS: readonly Patient[] = PATIENT_ROWS.map(
  ([medicalRecordNumber, familyName, givenName, birthDate, administrativeSex, nss, ageDays]) => ({
    medicalRecordNumber,
    identifiers: identifiersFor(medicalRecordNumber, nss),
    familyName,
    givenName,
    birthDate,
    administrativeSex,
    lastUpdated: daysBefore(ageDays),
  }),
);

/**
 * `[mrn, visitNumber, class, hoursBeforeSeedEpoch, clinician, pointOfCare, room, bed]`
 *
 * Several patients carry more than one visit, and they are listed here out of chronological
 * order on purpose: the gateway is contractually required to return them newest-first, and a
 * fixture that happens to be pre-sorted would let a missing sort pass its own test.
 */
type EncounterRow = readonly [string, string, string, number, string, string, string, string];

const ENCOUNTER_ROWS: readonly EncounterRow[] = [
  ['MRN-88213', 'VN-556677', 'I', 0, 'Enrique Torres', 'WARD-3', '301', 'A'],
  ['MRN-88213', 'VN-551204', 'E', 26 * 24, 'Alma Delia Ramírez', 'URG', '12', ''],
  ['MRN-88213', 'VN-548890', 'O', 94 * 24, 'Mónica Ortega', 'CONS-EXT', '4', ''],
  ['MRN-10042', 'VN-560012', 'I', 6, 'Jorge Ramírez', 'WARD-1', '108', 'B'],
  ['MRN-10042', 'VN-559800', 'E', 51 * 24, 'Enrique Torres', 'URG', '3', ''],
  ['MRN-10043', 'VN-560104', 'O', 30, 'Cecilia Domínguez', 'CONS-EXT', '9', ''],
  ['MRN-10045', 'VN-560221', 'I', 78, 'Ignacio Vázquez', 'UCI', '2', 'A'],
  ['MRN-10047', 'VN-559440', 'I', 210, 'Jorge Ramírez', 'WARD-2', '215', 'C'],
  ['MRN-10052', 'VN-558903', 'I', 340, 'Guadalupe Hernández', 'WARD-3', '312', 'A'],
  ['MRN-10052', 'VN-557112', 'E', 700, 'Enrique Torres', 'URG', '8', ''],
  ['MRN-20101', 'VN-560330', 'O', 12, 'Mónica Ortega', 'CONS-EXT', '2', ''],
  ['MRN-20104', 'VN-559990', 'I', 120, 'Arturo Hernández', 'WARD-1', '104', 'A'],
  ['MRN-30201', 'VN-560401', 'P', 4, 'Claudia Núñez', 'PREADM', '', ''],
  ['MRN-30202', 'VN-558200', 'I', 520, 'Ignacio Vázquez', 'UCI', '1', 'B'],
  ['MRN-40301', 'VN-560150', 'E', 44, 'Enrique Torres', 'URG', '5', ''],
  ['MRN-50401', 'VN-560500', 'I', 2, 'Guadalupe Hernández', 'WARD-2', '208', 'A'],
  ['MRN-60501', 'VN-557800', 'I', 830, 'Arturo Hernández', 'WARD-1', '110', 'B'],
  ['MRN-70601', 'VN-560280', 'O', 20, 'Cecilia Domínguez', 'CONS-EXT', '7', ''],
  ['MRN-80701', 'VN-560460', 'E', 9, 'Jorge Ramírez', 'URG', '1', ''],
  ['MRN-90801', 'VN-559600', 'O', 168, 'Mónica Ortega', 'CONS-EXT', '11', ''],
];

const HOUR_MS = 60 * 60 * 1000;

export const SAMPLE_ENCOUNTERS: readonly Encounter[] = ENCOUNTER_ROWS.map(
  ([
    medicalRecordNumber,
    visitNumber,
    patientClass,
    hoursAgo,
    attendingClinician,
    pointOfCare,
    room,
    bed,
  ]) => ({
    visitNumber,
    medicalRecordNumber,
    patientClass,
    admittedAt: new Date(SEED_EPOCH - hoursAgo * HOUR_MS).toISOString(),
    attendingClinician,
    location: { pointOfCare, room, bed, facility: 'HGS_PUEBLA' },
  }),
);

/**
 * A pipeline snapshot that is deliberately not all-green.
 *
 * A demo dashboard where every light is healthy proves only that the healthy branch renders.
 * This one carries a degraded stage with a non-zero `failed` counter and one stage whose
 * probe has not answered, so the interesting states — the ones an operator actually has to
 * read under pressure — are on screen by default.
 */
export const SAMPLE_PIPELINE_STATUS: PipelineStatus = {
  observedAt: new Date(SEED_EPOCH).toISOString(),
  services: [
    {
      id: 'hl7-ingest',
      displayName: 'HL7 ingest',
      runtime: 'Java 21',
      stage: 'ingest',
      health: 'healthy',
      detail: 'MLLP listener accepting on :2575',
      checkedAt: new Date(SEED_EPOCH - 4_000).toISOString(),
      counters: { accepted: 48_213, rejected: 96, failed: 0 },
    },
    {
      id: 'fhir-mapper',
      displayName: 'FHIR mapper',
      runtime: 'Kotlin',
      stage: 'transform',
      health: 'degraded',
      detail: 'Consumer lag 4,812 on clinical.admissions.v1; Mongo writes retrying',
      checkedAt: new Date(SEED_EPOCH - 3_000).toISOString(),
      counters: { accepted: 43_401, rejected: 12, failed: 137 },
    },
    {
      id: 'patient-gateway',
      displayName: 'Patient gateway',
      runtime: 'Go',
      stage: 'read',
      health: 'healthy',
      detail: 'gRPC serving on :9090',
      checkedAt: new Date(SEED_EPOCH - 2_000).toISOString(),
      counters: null,
    },
    {
      id: 'connect-proxy',
      displayName: 'Connect proxy',
      runtime: 'Envoy',
      stage: 'edge',
      health: 'healthy',
      detail: 'gRPC-Web bridge for browser clients',
      checkedAt: new Date(SEED_EPOCH - 2_500).toISOString(),
      counters: null,
    },
    {
      id: 'claims-edi',
      displayName: 'Claims EDI 837P',
      runtime: '.NET',
      stage: 'claims',
      health: 'unknown',
      detail: 'No probe response since 14:12; last known state was healthy',
      checkedAt: new Date(SEED_EPOCH - 18 * 60 * 1000).toISOString(),
      counters: { accepted: 2_904, rejected: 41, failed: 3 },
    },
  ],
};
