import { Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import {
  EMPTY,
  Observable,
  Subject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  map,
  merge,
  skip,
  switchMap,
  tap,
} from 'rxjs';

import type { Patient, PatientPage } from '../../domain/patient';
import {
  DEFAULT_PAGE_SIZE,
  GatewayError,
  MIN_QUERY_LENGTH,
  PatientGateway,
} from '../../data/patient-gateway';

/**
 * Debounce window for the search box, in milliseconds.
 *
 * 250ms is chosen against typing cadence rather than against network cost. Comfortable
 * typing lands keys 120–200ms apart, so a shorter window fires mid-word and the operator
 * watches results churn through partial names — which is not just wasteful, it is
 * *confusing*, because a partial surname matches a different set of patients and the screen
 * appears to answer a question nobody asked. Much above 300ms and the box feels broken.
 */
export const SEARCH_DEBOUNCE_MS = 250;

/**
 * What the results region is currently showing. One closed union rather than a handful of
 * booleans.
 *
 * `isLoading && hasError && results.length` is eight representable combinations, of which
 * roughly three are reachable and none of the other five are handled — that is the shape of
 * a template that renders a spinner over a stale list over an error banner, all at once. A
 * union makes the reachable set the *only* set, and the template's `@switch` exhaustive.
 *
 * `loading-more` is separate from `loading` because they render differently and must: a new
 * search replaces the list and shows a skeleton, while a "load more" keeps the existing rows
 * on screen — blanking a list the operator is reading in order to append to it is how you
 * lose their place.
 */
export type SearchPhase =
  /** Nothing typed yet. */
  | 'idle'
  /** Typed, but below the minimum length. No request was made. */
  | 'too-short'
  /** First page in flight; the list is empty or being replaced. */
  | 'loading'
  /** A subsequent page is in flight; existing rows stay on screen. */
  | 'loading-more'
  /** Results are current — possibly zero of them. */
  | 'ready'
  | 'error';

/**
 * A request the store intends to make. Search and pagination are modelled as one union
 * because they must flow through one `switchMap`: they compete for the same result list, and
 * whichever was asked for most recently is the one whose answer is correct.
 */
type Intent =
  | { readonly kind: 'search'; readonly term: string; readonly pageSize: number }
  | {
      readonly kind: 'page';
      readonly term: string;
      readonly pageSize: number;
      readonly pageToken: string;
    };

/**
 * All state behind the patient search screen.
 *
 * ## Why a service and not component fields
 *
 * The search screen is one component today. The state is here anyway because this is where
 * the concurrency rules live, and concurrency rules that sit in a component get re-derived
 * — slightly differently — the first time someone needs a second entry point to search. It
 * is provided by the route, so its lifetime is the screen's, not the application's.
 *
 * ## Signals for state, RxJS for time
 *
 * The split is deliberate and consistent: every value the template reads is a signal, and
 * every *sequencing* concern — debounce, cancellation, ordering — is RxJS. There is no
 * `BehaviorSubject` holding state and no `async` pipe. Mixing the two means a component that
 * has to know which values need `| async` and which do not, and the reason a given field is
 * one or the other stops being legible within a release or two.
 *
 * RxJS is not avoided, because signals genuinely cannot express what this screen needs:
 * there is no signal primitive for "debounce, then cancel the previous request". `switchMap`
 * is exactly that primitive and it is thirty years old.
 *
 * ## The stale-response rule
 *
 * A search box is the canonical race. Type "gar", then "garcia": two requests are in flight,
 * and if the first answers second, the screen shows patients matching "gar" under a box
 * reading "garcia". In a clinical search this is not a cosmetic bug — an operator selects a
 * patient from a list they believe answers what they typed.
 *
 * Two mechanisms, in layers:
 *
 * 1. `switchMap` unsubscribes from the previous inner observable when a new intent arrives.
 *    Over HTTP that aborts the request outright, so the response is never delivered. This is
 *    the real mechanism and it is sufficient on its own.
 * 2. A monotonic sequence number, checked at the point where results are written into
 *    signals. This is redundant *today*. It is here because the failure it guards against is
 *    invisible: changing `switchMap` to `mergeMap` or `concatMap` is a one-word edit that
 *    compiles, passes every test that does not specifically interleave responses, and
 *    reintroduces the race. The sequence check turns that edit into a wrong-but-safe
 *    behaviour (a dropped update) rather than a wrong-and-dangerous one, and it states the
 *    invariant in a place a reader will actually look — next to the write.
 */
@Injectable()
export class PatientSearchStore {
  private readonly gateway = inject(PatientGateway);

  // --- inputs, written by the component's typed form -------------------------------------

  private readonly termInput = signal('');
  private readonly pageSizeInput = signal<number>(DEFAULT_PAGE_SIZE);

  // --- results ---------------------------------------------------------------------------

  private readonly patientsState = signal<readonly Patient[]>([]);
  private readonly totalMatchedState = signal(0);
  private readonly nextPageTokenState = signal<string | null>(null);
  private readonly phaseState = signal<SearchPhase>('idle');
  private readonly errorState = signal<GatewayError | null>(null);

  /**
   * Index of the first row appended by the most recent "load more", or `-1`.
   *
   * Exposed so the view can move focus there. It lives in the store rather than being
   * inferred in the component because only the store knows whether the last successful
   * response replaced the list or extended it — from the outside, "the array got longer" is
   * also what a new search returning more rows looks like.
   */
  private readonly appendedFromState = signal(-1);

  readonly term = this.termInput.asReadonly();
  readonly patients = this.patientsState.asReadonly();
  readonly totalMatched = this.totalMatchedState.asReadonly();
  readonly phase = this.phaseState.asReadonly();
  readonly error = this.errorState.asReadonly();
  readonly appendedFrom = this.appendedFromState.asReadonly();

  // --- derived ---------------------------------------------------------------------------

  readonly isBusy = computed(
    () => this.phaseState() === 'loading' || this.phaseState() === 'loading-more',
  );

  /**
   * Whether another page exists.
   *
   * Keyed on the presence of a token and nothing else. Deriving it from
   * `patients().length < totalMatched()` would be wrong in a way that only appears under
   * concurrent writes: `totalMatched` is a count taken when the page was produced, so an
   * admission between page one and page two makes the arithmetic disagree with the cursor,
   * and the button either disappears with rows left or persists onto an empty page.
   */
  readonly hasMore = computed(() => this.nextPageTokenState() !== null);

  readonly canLoadMore = computed(() => this.hasMore() && !this.isBusy());

  /**
   * The line read out to assistive technology when results change.
   *
   * Composed here rather than assembled in the template so there is exactly one string, and
   * so it can be asserted in a test. The counts are phrased as "N of M matched" because a
   * cursor-paginated list genuinely shows a prefix of the matches, and "showing 25 results"
   * would tell a screen-reader user the search found 25 patients when it found 340.
   */
  readonly resultSummary = computed(() => {
    // Read once into a local. `switch (this.phaseState())` would compile, but the `never`
    // check in the default arm would not: TypeScript cannot narrow across repeated calls to
    // the same function, so the exhaustiveness assertion — the entire reason the default arm
    // exists — silently stops working.
    const phase = this.phaseState();
    switch (phase) {
      case 'idle':
        return '';
      case 'too-short':
        return `Enter at least ${MIN_QUERY_LENGTH} characters to search.`;
      case 'loading':
        return 'Searching.';
      case 'loading-more':
        return 'Loading more results.';
      case 'error':
        return `Search failed. ${this.errorState()?.message ?? ''}`.trim();
      case 'ready': {
        const shown = this.patientsState().length;
        const total = this.totalMatchedState();
        if (total === 0) {
          return 'No patients matched.';
        }
        return shown < total
          ? `Showing ${shown} of ${total} matched patients.`
          : `Showing all ${total} matched patients.`;
      }
      default: {
        const unreachable: never = phase;
        throw new Error(`unhandled phase: ${String(unreachable)}`);
      }
    }
  });

  // --- request sequencing ------------------------------------------------------------------

  /** Incremented for every gateway call issued. See the stale-response rule above. */
  private issued = 0;

  /** Re-issue of the intent that failed, driven by the error panel's retry control. */
  private readonly retries = new Subject<Intent>();
  private readonly pageRequests = new Subject<Intent>();
  private lastIntent: Intent | null = null;

  constructor() {
    /**
     * Term changes: debounced, then de-duplicated.
     *
     * The order matters and is the opposite of what reads naturally.
     * `debounceTime → distinctUntilChanged` collapses a burst of keystrokes to its final
     * value and *then* asks whether that value is new — so typing "garcia", deleting back to
     * "gar", and retyping "garcia" within the window issues nothing at all, because the
     * settled value never changed. Reversed, `distinctUntilChanged` sees every intermediate
     * keystroke, passes them all (each differs from the last), and the debounce only ever
     * removes the tail.
     */
    const termChanges = toObservable(this.termInput).pipe(
      map((term) => term.trim()),
      debounceTime(SEARCH_DEBOUNCE_MS),
      distinctUntilChanged(),
    );

    /**
     * Page-size changes restart the search at page one, undebounced.
     *
     * Undebounced because a select is a single deliberate act, not a burst; making the
     * operator wait 250ms after choosing "50 per page" reads as lag.
     *
     * Restarting rather than continuing because the page token encodes an offset into a
     * result set that was sliced at the old size. Handing it back with a new size produces a
     * technically valid but incoherent page — a jump or an overlap, depending on direction —
     * and the operator has no way to tell that is what happened.
     *
     * `skip(1)` drops the initial value that `toObservable` replays on subscribe. Without
     * it, startup issues two identical searches: one from the term stream and one from here.
     */
    const pageSizeChanges = toObservable(this.pageSizeInput).pipe(distinctUntilChanged(), skip(1));

    const searches: Observable<Intent> = merge(
      termChanges.pipe(
        map((term) => ({ kind: 'search', term, pageSize: this.pageSizeInput() }) as const),
      ),
      pageSizeChanges.pipe(
        map((pageSize) => ({ kind: 'search', term: this.termInput().trim(), pageSize }) as const),
      ),
    );

    merge(searches, this.pageRequests, this.retries)
      .pipe(
        tap((intent) => this.beginIntent(intent)),
        // The single point of cancellation. Everything above competes for the same result
        // list, so the newest intent must win and the previous request must be dropped —
        // not merged, not queued.
        switchMap((intent) => this.execute(intent)),
        takeUntilDestroyed(),
      )
      .subscribe();
  }

  // --- commands ----------------------------------------------------------------------------

  /** Called from the form's `valueChanges`. */
  setTerm(term: string): void {
    this.termInput.set(term);
  }

  setPageSize(pageSize: number): void {
    this.pageSizeInput.set(pageSize);
  }

  /**
   * Fetches the next page and appends it.
   *
   * Guarded rather than merely disabled in the template: a keyboard user can activate a
   * button between the response landing and change detection running, and a double-fire here
   * would append the same page twice — a duplicated patient row, which in a clinical list is
   * indistinguishable from two patients with identical details.
   */
  loadMore(): void {
    const pageToken = this.nextPageTokenState();
    if (pageToken === null || this.isBusy()) {
      return;
    }
    this.pageRequests.next({
      kind: 'page',
      term: this.termInput().trim(),
      pageSize: this.pageSizeInput(),
      pageToken,
    });
  }

  /**
   * Re-issues the intent that failed.
   *
   * An explicit command rather than something the term stream can produce, because
   * `distinctUntilChanged` correctly refuses to re-emit an unchanged term — so after a
   * failure, retyping the same name does nothing. Without a retry control the only recovery
   * is to change the search and change it back, which nobody discovers.
   */
  retry(): void {
    const intent = this.lastIntent;
    if (intent === null || this.isBusy()) {
      return;
    }
    this.retries.next(intent);
  }

  // --- execution ---------------------------------------------------------------------------

  private beginIntent(intent: Intent): void {
    this.lastIntent = intent;
    this.errorState.set(null);
    this.appendedFromState.set(-1);

    if (intent.kind === 'page') {
      this.phaseState.set('loading-more');
      return;
    }

    // Rune count, not `String.length`, to match the server's `len([]rune(...))`. For a
    // two-character rule the difference only bites on astral-plane input, but the two rules
    // disagreeing at all means a query the client considers long enough is rejected by the
    // server as too short — an error the operator cannot act on because the box looks fine.
    if ([...intent.term].length < MIN_QUERY_LENGTH) {
      // Results are cleared rather than left stale. A list still showing matches for
      // "garcia" under an empty search box is the screen asserting something untrue.
      this.patientsState.set([]);
      this.totalMatchedState.set(0);
      this.nextPageTokenState.set(null);
      this.phaseState.set(intent.term.length === 0 ? 'idle' : 'too-short');
      return;
    }

    this.phaseState.set('loading');
  }

  /**
   * Issues one intent, or nothing when the intent was resolved locally.
   *
   * Returns an observable that never errors: a failure is written to state and swallowed
   * with `EMPTY`. If the error escaped, it would tear down the outer subscription and the
   * search box would silently stop working for the rest of the session — the classic RxJS
   * store bug, and one that is invisible in testing because the first failure is usually the
   * last thing a test does.
   */
  private execute(intent: Intent): Observable<void> {
    if (intent.kind === 'search' && [...intent.term].length < MIN_QUERY_LENGTH) {
      return EMPTY;
    }

    const sequence = ++this.issued;

    return this.gateway
      .searchPatients({
        query: intent.term,
        pageSize: intent.pageSize,
        ...(intent.kind === 'page' ? { pageToken: intent.pageToken } : {}),
      })
      .pipe(
        tap((page) => this.applyPage(sequence, intent, page)),
        map(() => undefined),
        catchError((error: unknown) => {
          this.applyError(sequence, error);
          return EMPTY;
        }),
      );
  }

  private applyPage(sequence: number, intent: Intent, page: PatientPage): void {
    if (sequence !== this.issued) {
      return;
    }

    if (intent.kind === 'page') {
      const existing = this.patientsState();
      // Concatenated, not merged or de-duplicated. The gateway guarantees a total order and
      // a cursor that resumes exactly where the previous page ended, so de-duplicating here
      // would paper over a broken cursor rather than reveal it.
      this.patientsState.set([...existing, ...page.patients]);
      this.appendedFromState.set(existing.length);
    } else {
      this.patientsState.set(page.patients);
      this.appendedFromState.set(-1);
    }

    this.totalMatchedState.set(page.totalMatched);
    this.nextPageTokenState.set(page.nextPageToken);
    this.phaseState.set('ready');
  }

  private applyError(sequence: number, error: unknown): void {
    if (sequence !== this.issued) {
      return;
    }
    this.errorState.set(
      error instanceof GatewayError
        ? error
        : new GatewayError('unknown', 'search failed', null, { cause: error }),
    );
    this.phaseState.set('error');
    // The result list is left as it was. On a failed "load more" the rows already on screen
    // are still valid, and discarding them would punish the operator for the network's
    // behaviour. `nextPageToken` is left alone too, so the retry resumes from the same
    // cursor rather than restarting the walk.
  }
}
