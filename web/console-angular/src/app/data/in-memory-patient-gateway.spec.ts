import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { fold, type Patient } from '../domain/patient';
import { IN_MEMORY_LATENCY_MS, InMemoryPatientGateway } from './in-memory-patient-gateway';
import { GatewayError, PatientGateway } from './patient-gateway';
import { SAMPLE_PATIENTS } from './sample-data';

/**
 * The pagination contract.
 *
 * These are the tests that make the in-memory gateway a stand-in rather than a stub. Every
 * assertion here is a rule the Go gateway also has to honour, so if this implementation
 * drifts, the UI developed against it drifts too — and the drift shows up as wrong patients
 * on a page boundary, which is the failure mode nobody notices in review.
 */
describe('InMemoryPatientGateway', () => {
  let gateway: PatientGateway;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: PatientGateway, useClass: InMemoryPatientGateway },
        // Latency pinned to zero: these specs are about the contract, not about timing.
        { provide: IN_MEMORY_LATENCY_MS, useValue: 0 },
      ],
    });
    gateway = TestBed.inject(PatientGateway);
  });

  /** Walks every page of a search and returns the flattened result plus the page count. */
  async function walkAll(
    query: string,
    pageSize: number,
  ): Promise<{ patients: Patient[]; pages: number; totalMatched: number }> {
    const patients: Patient[] = [];
    let pageToken: string | undefined;
    let pages = 0;
    let totalMatched = 0;

    do {
      const page = await firstValueFrom(
        gateway.searchPatients({
          query,
          pageSize,
          ...(pageToken === undefined ? {} : { pageToken }),
        }),
      );
      patients.push(...page.patients);
      totalMatched = page.totalMatched;
      pageToken = page.nextPageToken ?? undefined;
      pages += 1;

      // A cursor API that never terminates is the other half of this bug class, and an
      // unbounded loop in a test hangs the suite instead of failing it.
      expect(pages).toBeLessThan(50);
    } while (pageToken !== undefined);

    return { patients, pages, totalMatched };
  }

  describe('pagination', () => {
    it('walks a multi-page result set without repeating or omitting a patient', async () => {
      const { patients, pages, totalMatched } = await walkAll('garcia', 10);

      expect(pages).toBeGreaterThan(2);
      expect(patients).toHaveLength(totalMatched);

      // The assertion that actually catches a broken sort. With a non-total order, ties
      // straddle a page boundary in an arbitrary order and the walk both repeats and skips
      // records — and the *count* can still come out right, which is why counting alone is
      // not enough.
      const mrns = patients.map((p) => p.medicalRecordNumber);
      expect(new Set(mrns).size).toBe(mrns.length);
    });

    it('orders results identically regardless of where the page boundaries fall', async () => {
      // The real statement of "stable ordering": slicing the same result set at 3, 10 and
      // 100 must produce the same sequence. If the sort were not total, the sequences would
      // differ wherever a tie crossed a boundary.
      const byThree = await walkAll('garcia', 3);
      const byTen = await walkAll('garcia', 10);
      const oneShot = await walkAll('garcia', 100);

      expect(byThree.patients.map((p) => p.medicalRecordNumber)).toEqual(
        byTen.patients.map((p) => p.medicalRecordNumber),
      );
      expect(byTen.patients.map((p) => p.medicalRecordNumber)).toEqual(
        oneShot.patients.map((p) => p.medicalRecordNumber),
      );
      expect(oneShot.pages).toBe(1);
    });

    it('breaks surname ties on given name and then on MRN', async () => {
      const { patients } = await walkAll('garcia', 100);
      expect(patients.length).toBeGreaterThan(20);

      // Asserted pairwise against the documented comparator rather than by re-sorting and
      // comparing arrays. Re-sorting with the same comparator the implementation uses is a
      // tautology; walking adjacent pairs states the actual invariant — the sequence is
      // non-decreasing, and no two adjacent entries compare equal, which is what makes the
      // order *total* and therefore safe to paginate.
      for (let i = 1; i < patients.length; i++) {
        const previous = patients[i - 1]!;
        const current = patients[i]!;
        const comparison =
          fold(previous.familyName).localeCompare(fold(current.familyName)) ||
          fold(previous.givenName).localeCompare(fold(current.givenName)) ||
          previous.medicalRecordNumber.localeCompare(current.medicalRecordNumber);
        expect(comparison).toBeLessThan(0);
      }
    });

    it('omits nextPageToken on the last page rather than returning an empty one', async () => {
      const total = SAMPLE_PATIENTS.filter((p) =>
        p.familyName.toLowerCase().startsWith('hern'),
      ).length;
      expect(total).toBeGreaterThan(0);

      // Page size chosen to divide the result set exactly. This is the case where
      // `slice.length === pageSize` is true on the final page — the version that infers
      // "more results" from a full page mints a token here and the walk visits an empty page.
      const page = await firstValueFrom(gateway.searchPatients({ query: 'hern', pageSize: total }));
      expect(page.patients).toHaveLength(total);
      expect(page.nextPageToken).toBeNull();
    });

    it('reports totalMatched consistently across pages', async () => {
      const first = await firstValueFrom(gateway.searchPatients({ query: 'garcia', pageSize: 5 }));
      expect(first.nextPageToken).not.toBeNull();

      const second = await firstValueFrom(
        gateway.searchPatients({
          query: 'garcia',
          pageSize: 5,
          pageToken: first.nextPageToken ?? '',
        }),
      );
      expect(second.totalMatched).toBe(first.totalMatched);
      expect(first.totalMatched).toBeGreaterThan(5);
    });

    it('rejects a page token minted for a different query', async () => {
      const page = await firstValueFrom(gateway.searchPatients({ query: 'garcia', pageSize: 5 }));

      // The failure this prevents: an arbitrary slice of a *different* result set rendered
      // under the new search term — plausible-looking rows for the wrong patients.
      await expect(
        firstValueFrom(
          gateway.searchPatients({
            query: 'hernandez',
            pageSize: 5,
            pageToken: page.nextPageToken ?? '',
          }),
        ),
      ).rejects.toMatchObject({ kind: 'invalid-request' });
    });

    it('accepts a token across an equivalent spelling of the same query', async () => {
      // "Núñez" and "nunez" match the same patients, so a cursor minted by one must be
      // honoured by the other — otherwise the operator's own autocorrect breaks pagination.
      const page = await firstValueFrom(gateway.searchPatients({ query: 'Núñez', pageSize: 1 }));
      expect(page.nextPageToken).not.toBeNull();

      const next = await firstValueFrom(
        gateway.searchPatients({
          query: 'nunez',
          pageSize: 1,
          pageToken: page.nextPageToken ?? '',
        }),
      );
      expect(next.patients).toHaveLength(1);
    });

    it('rejects a malformed page token', async () => {
      await expect(
        firstValueFrom(gateway.searchPatients({ query: 'garcia', pageToken: 'nonsense!!' })),
      ).rejects.toBeInstanceOf(GatewayError);
    });

    it('clamps an oversized page size instead of failing', async () => {
      const page = await firstValueFrom(gateway.searchPatients({ query: 'ar', pageSize: 5_000 }));
      expect(page.patients.length).toBeLessThanOrEqual(100);
    });
  });

  describe('matching', () => {
    it('refuses a query below the minimum length', async () => {
      await expect(firstValueFrom(gateway.searchPatients({ query: 'g' }))).rejects.toMatchObject({
        kind: 'invalid-request',
      });
    });

    it('matches across accents in both directions', async () => {
      // Both spellings are present in the fixture on purpose: one hospital's registration
      // strips diacritics and another's does not, and a search that treats them as different
      // people reports "no such patient" for someone who is admitted.
      const accented = await firstValueFrom(gateway.searchPatients({ query: 'Núñez' }));
      const plain = await firstValueFrom(gateway.searchPatients({ query: 'nunez' }));
      expect(accented.totalMatched).toBe(plain.totalMatched);
      expect(accented.totalMatched).toBeGreaterThan(1);
    });

    it('matches identifiers as well as names', async () => {
      const byMrn = await firstValueFrom(gateway.searchPatients({ query: 'MRN-88213' }));
      expect(byMrn.patients.map((p) => p.medicalRecordNumber)).toContain('MRN-88213');

      const byNss = await firstValueFrom(gateway.searchPatients({ query: 'NSS-4471120' }));
      expect(byNss.patients.map((p) => p.medicalRecordNumber)).toContain('MRN-88213');
    });
  });

  describe('getPatient', () => {
    it('returns the patient for a known MRN', async () => {
      const patient = await firstValueFrom(gateway.getPatient('MRN-88213'));
      expect(patient.familyName).toBe('Luna');
    });

    it('rejects with not-found for an unknown MRN', async () => {
      await expect(firstValueFrom(gateway.getPatient('MRN-DOES-NOT-EXIST'))).rejects.toMatchObject({
        kind: 'not-found',
      });
    });

    it('matches identifiers exactly, not case-insensitively', async () => {
      // Names fold; identifiers must not. On a system where `mrn-88213` and `MRN-88213` are
      // distinct identifiers, folding resolves one patient's URL to another's record.
      await expect(firstValueFrom(gateway.getPatient('mrn-88213'))).rejects.toMatchObject({
        kind: 'not-found',
      });
    });
  });

  describe('listEncounters', () => {
    it('returns visits newest first', async () => {
      const encounters = await firstValueFrom(gateway.listEncounters('MRN-88213'));
      expect(encounters.length).toBeGreaterThan(1);

      const times = encounters.map((e) => Date.parse(e.admittedAt));
      expect(times).toEqual([...times].sort((a, b) => b - a));
    });

    it('returns an empty list for a patient with no visits', async () => {
      const withoutVisits = SAMPLE_PATIENTS.find((p) => p.medicalRecordNumber === 'MRN-10044');
      expect(withoutVisits).toBeDefined();
      await expect(firstValueFrom(gateway.listEncounters('MRN-10044'))).resolves.toEqual([]);
    });

    it('distinguishes "no visits" from "no such patient"', async () => {
      // Returning `[]` for an unknown MRN would render a detail page for a patient this
      // system has never seen.
      await expect(firstValueFrom(gateway.listEncounters('MRN-NOPE'))).rejects.toMatchObject({
        kind: 'not-found',
      });
    });
  });
});
