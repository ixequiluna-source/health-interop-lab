import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { PatientGateway } from '../../data/patient-gateway';
import {
  ControllableGateway,
  gatewayError,
  samplePage,
  samplePatient,
} from '../../../testing/controllable-gateway';
import { advance, flushReactive, useRealTime, useVirtualTime } from '../../../testing/time';
import { PatientSearchStore, SEARCH_DEBOUNCE_MS } from './patient-search-store';

describe('PatientSearchStore', () => {
  let store: PatientSearchStore;
  let gateway: ControllableGateway;

  beforeEach(() => {
    useVirtualTime();
    gateway = new ControllableGateway();
    TestBed.configureTestingModule({
      providers: [PatientSearchStore, { provide: PatientGateway, useValue: gateway }],
    });
    store = TestBed.inject(PatientSearchStore);
    // Flush the initial replay from `toObservable`, so the counts below only reflect what
    // the test itself typed.
    advance(SEARCH_DEBOUNCE_MS);
  });

  afterEach(() => {
    useRealTime();
  });

  /** Simulates typing and letting the debounce window elapse. */
  function type(term: string): void {
    store.setTerm(term);
    flushReactive();
    advance(SEARCH_DEBOUNCE_MS);
  }

  /** Simulates typing without waiting for the debounce to settle. */
  function typeWithoutSettling(term: string, elapsed = 0): void {
    store.setTerm(term);
    flushReactive();
    if (elapsed > 0) {
      advance(elapsed);
    }
  }

  describe('debounce and minimum length', () => {
    it('issues nothing until the debounce window elapses', () => {
      typeWithoutSettling('garcia', SEARCH_DEBOUNCE_MS - 1);
      expect(gateway.searchCalls).toHaveLength(0);

      advance(1);
      expect(gateway.searchCalls).toHaveLength(1);
    });

    it('collapses a burst of keystrokes into one request', () => {
      // The behaviour that makes the difference between one query and six against a
      // patient index, per operator, per search.
      for (const partial of ['g', 'ga', 'gar', 'garc', 'garci', 'garcia']) {
        typeWithoutSettling(partial, 40);
      }
      advance(SEARCH_DEBOUNCE_MS);

      expect(gateway.searchCalls).toHaveLength(1);
      expect(gateway.searchCalls[0]?.request).toMatchObject({ query: 'garcia' });
    });

    it('issues nothing for a query below the minimum length', () => {
      type('g');

      // Not merely "did not render" — no request was made at all. A one-character search
      // matches most of the index, which is a bulk disclosure rather than a query.
      expect(gateway.searchCalls).toHaveLength(0);
      expect(store.phase()).toBe('too-short');
    });

    it('returns to idle, not too-short, when the box is cleared', () => {
      type('g');
      expect(store.phase()).toBe('too-short');

      type('');
      // An empty box is not an error the operator should be told about.
      expect(store.phase()).toBe('idle');
    });

    it('clears stale results when the term falls below the minimum', () => {
      type('garcia');
      gateway.searchCalls[0]?.resolve(samplePage({ patients: [samplePatient()], totalMatched: 1 }));
      expect(store.patients()).toHaveLength(1);

      type('g');

      // Leaving the previous matches on screen under a one-character box would be the
      // screen asserting something untrue about what was searched for.
      expect(store.patients()).toHaveLength(0);
      expect(store.totalMatched()).toBe(0);
    });

    it('does not re-issue a search when the settled term is unchanged', () => {
      // "garcia" → "garci" → "garcia" inside one debounce window settles to the value it
      // started at, so there is nothing new to ask.
      type('garcia');
      expect(gateway.searchCalls).toHaveLength(1);

      typeWithoutSettling('garci', 50);
      typeWithoutSettling('garcia', 50);
      advance(SEARCH_DEBOUNCE_MS);

      expect(gateway.searchCalls).toHaveLength(1);
    });

    it('trims surrounding whitespace before deciding a term is new', () => {
      type('garcia');
      type('  garcia  ');
      expect(gateway.searchCalls).toHaveLength(1);
    });
  });

  describe('stale responses', () => {
    it('discards an older response that arrives after a newer one', () => {
      // The canonical search-box race. In a clinical console it is not cosmetic: the
      // operator picks a patient from a list they believe answers what they typed.
      type('gar');
      type('garcia');
      expect(gateway.searchCalls).toHaveLength(2);

      const stale = gateway.searchCalls[0]!;
      const fresh = gateway.searchCalls[1]!;
      expect(stale.request).toMatchObject({ query: 'gar' });
      expect(fresh.request).toMatchObject({ query: 'garcia' });

      // Answer the *newer* request first…
      fresh.resolve(
        samplePage({
          patients: [samplePatient({ medicalRecordNumber: 'MRN-FRESH' })],
          totalMatched: 1,
        }),
      );
      expect(store.patients().map((p) => p.medicalRecordNumber)).toEqual(['MRN-FRESH']);

      // …then deliver the older one late. This is exactly the out-of-order delivery the
      // guard exists for, and the fake gateway pushes it regardless of subscription state
      // so the assertion is about the store's behaviour and not about the fake's politeness.
      stale.resolve(
        samplePage({
          patients: [samplePatient({ medicalRecordNumber: 'MRN-STALE' })],
          totalMatched: 99,
        }),
      );

      expect(store.patients().map((p) => p.medicalRecordNumber)).toEqual(['MRN-FRESH']);
      expect(store.totalMatched()).toBe(1);
    });

    it('cancels the superseded request rather than merely ignoring its answer', () => {
      // The stronger property, and the one that keeps a slow backend from doing work nobody
      // will look at: `switchMap` unsubscribes, which over HTTP aborts the request.
      type('gar');
      type('garcia');

      expect(gateway.searchCalls[0]?.cancelled).toBe(true);
      expect(gateway.searchCalls[1]?.cancelled).toBe(false);
    });

    it('discards a stale failure as well as a stale success', () => {
      // Otherwise a timeout on an abandoned search paints an error banner over results that
      // loaded perfectly well.
      type('gar');
      type('garcia');

      gateway.searchCalls[1]?.resolve(samplePage({ totalMatched: 1 }));
      gateway.searchCalls[0]?.fail(gatewayError('unavailable'));

      expect(store.phase()).toBe('ready');
      expect(store.error()).toBeNull();
    });

    it('cancels an in-flight page fetch when the search term changes', () => {
      type('garcia');
      gateway.searchCalls[0]?.resolve(samplePage({ nextPageToken: 'page-2', totalMatched: 40 }));

      store.loadMore();
      flushReactive();
      expect(gateway.searchCalls).toHaveLength(2);

      type('hernandez');

      // A page of "garcia" results appended to a "hernandez" list would be the same class of
      // bug as a stale first page, and it is reached through a different code path.
      expect(gateway.searchCalls[1]?.cancelled).toBe(true);
      gateway.searchCalls[1]?.resolve(
        samplePage({ patients: [samplePatient({ medicalRecordNumber: 'MRN-GARCIA-P2' })] }),
      );
      expect(store.patients().map((p) => p.medicalRecordNumber)).not.toContain('MRN-GARCIA-P2');
    });
  });

  describe('cursor pagination', () => {
    beforeEach(() => {
      type('garcia');
      gateway.searchCalls[0]?.resolve(
        samplePage({
          patients: [samplePatient({ medicalRecordNumber: 'MRN-1' })],
          nextPageToken: 'opaque-token-1',
          totalMatched: 3,
        }),
      );
    });

    it('sends the token back verbatim', () => {
      store.loadMore();
      flushReactive();

      expect(gateway.searchCalls[1]?.request).toMatchObject({
        query: 'garcia',
        pageToken: 'opaque-token-1',
      });
    });

    it('appends rather than replacing, and keeps rows visible while loading', () => {
      store.loadMore();
      flushReactive();

      // The reason `loading-more` is a distinct phase: blanking a list the operator is
      // reading in order to append to it loses their place.
      expect(store.phase()).toBe('loading-more');
      expect(store.patients()).toHaveLength(1);

      gateway.searchCalls[1]?.resolve(
        samplePage({
          patients: [samplePatient({ medicalRecordNumber: 'MRN-2' })],
          nextPageToken: null,
          totalMatched: 3,
        }),
      );

      expect(store.patients().map((p) => p.medicalRecordNumber)).toEqual(['MRN-1', 'MRN-2']);
    });

    it('reports the index of the first appended row so the view can move focus there', () => {
      store.loadMore();
      flushReactive();
      gateway.searchCalls[1]?.resolve(
        samplePage({ patients: [samplePatient({ medicalRecordNumber: 'MRN-2' })] }),
      );

      expect(store.appendedFrom()).toBe(1);
    });

    it('does not report an append for a fresh search', () => {
      // Moving focus into the result list while the operator is typing would make the search
      // box unusable, so the two cases have to be distinguishable from outside the store.
      type('hernandez');
      gateway.searchCalls[1]?.resolve(samplePage({ totalMatched: 1 }));

      expect(store.appendedFrom()).toBe(-1);
    });

    it('ends pagination on an absent token, not on a short page', () => {
      // A cursor API may return a short page and still have more; inferring the end from
      // page length silently truncates a result set.
      expect(store.hasMore()).toBe(true);

      store.loadMore();
      flushReactive();
      gateway.searchCalls[1]?.resolve(
        samplePage({ patients: [], nextPageToken: null, totalMatched: 3 }),
      );

      expect(store.hasMore()).toBe(false);
    });

    it('ignores a second loadMore while one is in flight', () => {
      // A keyboard user can activate the button twice between the response landing and
      // change detection running; appending the same page twice produces a duplicated
      // patient row, which is indistinguishable from two patients with identical details.
      store.loadMore();
      flushReactive();
      store.loadMore();
      flushReactive();

      expect(gateway.searchCalls).toHaveLength(2);
    });

    it('ignores loadMore when there is no next page', () => {
      gateway.searchCalls[0]?.resolve(samplePage({ nextPageToken: null }));
      type('hernandez');
      gateway.searchCalls[1]?.resolve(samplePage({ nextPageToken: null, totalMatched: 1 }));

      const before = gateway.searchCalls.length;
      store.loadMore();
      flushReactive();

      expect(gateway.searchCalls).toHaveLength(before);
    });
  });

  describe('failure handling', () => {
    it('surfaces the error and keeps the store usable', () => {
      type('garcia');
      gateway.searchCalls[0]?.fail(gatewayError('unavailable', 'gateway draining'));

      expect(store.phase()).toBe('error');
      expect(store.error()?.kind).toBe('unavailable');

      // The property that matters most: a failure must not tear down the subscription. If it
      // did, the search box would silently stop working for the rest of the session — and
      // the first failure is usually the last thing a test does, so the bug survives review.
      type('hernandez');
      expect(gateway.searchCalls).toHaveLength(2);
    });

    it('keeps existing rows when a load-more fails', () => {
      type('garcia');
      gateway.searchCalls[0]?.resolve(
        samplePage({
          patients: [samplePatient({ medicalRecordNumber: 'MRN-1' })],
          nextPageToken: 'token',
          totalMatched: 9,
        }),
      );

      store.loadMore();
      flushReactive();
      gateway.searchCalls[1]?.fail(gatewayError('unavailable'));

      // Discarding rows that are still perfectly valid punishes the operator for the
      // network's behaviour.
      expect(store.patients()).toHaveLength(1);
      expect(store.phase()).toBe('error');
    });

    it('re-issues the failed intent on retry, resuming from the same cursor', () => {
      type('garcia');
      gateway.searchCalls[0]?.resolve(samplePage({ nextPageToken: 'token-2', totalMatched: 9 }));
      store.loadMore();
      flushReactive();
      gateway.searchCalls[1]?.fail(gatewayError('unavailable'));

      store.retry();
      flushReactive();

      expect(gateway.searchCalls).toHaveLength(3);
      expect(gateway.searchCalls[2]?.request).toMatchObject({ pageToken: 'token-2' });
    });

    it('offers retry after a failure that an unchanged term cannot re-trigger', () => {
      // `distinctUntilChanged` correctly refuses to re-emit an unchanged term, so without an
      // explicit retry the only recovery is to change the search and change it back.
      type('garcia');
      gateway.searchCalls[0]?.fail(gatewayError('unavailable'));

      type('garcia');
      expect(gateway.searchCalls).toHaveLength(1);

      store.retry();
      flushReactive();
      expect(gateway.searchCalls).toHaveLength(2);
    });
  });

  describe('page size', () => {
    it('restarts the search from page one when the page size changes', () => {
      type('garcia');
      gateway.searchCalls[0]?.resolve(samplePage({ nextPageToken: 'token', totalMatched: 40 }));

      store.setPageSize(50);
      flushReactive();

      // Continuing with the old token would slice a result set that was paginated at the old
      // size — a jump or an overlap, with nothing on screen to say that is what happened.
      const request = gateway.searchCalls[1]?.request;
      expect(request).toMatchObject({ query: 'garcia', pageSize: 50 });
      expect(request).not.toHaveProperty('pageToken');
    });

    it('does not debounce a page-size change', () => {
      type('garcia');
      store.setPageSize(10);
      flushReactive();

      // A select is one deliberate act, not a burst; a 250ms wait after choosing reads as lag.
      expect(gateway.searchCalls).toHaveLength(2);
    });
  });

  describe('result summary for assistive technology', () => {
    it('states how many of the matches are on screen, not just how many are shown', () => {
      type('garcia');
      gateway.searchCalls[0]?.resolve(
        samplePage({
          patients: [samplePatient({ medicalRecordNumber: 'MRN-1' })],
          nextPageToken: 'more',
          totalMatched: 340,
        }),
      );

      // "1 result" would tell a screen-reader user the search found one patient when it
      // found 340 and is showing a prefix.
      expect(store.resultSummary()).toBe('Showing 1 of 340 matched patients.');
    });

    it('says so plainly when nothing matched', () => {
      type('garcia');
      gateway.searchCalls[0]?.resolve(samplePage({ patients: [], totalMatched: 0 }));

      expect(store.resultSummary()).toBe('No patients matched.');
    });

    it('drops the "of N" once the whole result set is on screen', () => {
      type('garcia');
      gateway.searchCalls[0]?.resolve(
        samplePage({ patients: [samplePatient()], nextPageToken: null, totalMatched: 1 }),
      );

      expect(store.resultSummary()).toBe('Showing all 1 matched patients.');
    });
  });
});
