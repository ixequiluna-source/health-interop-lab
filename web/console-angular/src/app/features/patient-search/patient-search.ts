import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  inject,
  viewChildren,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
  type FormControl,
} from '@angular/forms';
import { RouterLink } from '@angular/router';

import { MIN_QUERY_LENGTH } from '../../data/patient-gateway';
import { fullName } from '../../domain/patient';
import { PatientSearchStore } from './patient-search-store';

/**
 * The typed shape of the filter form.
 *
 * Declared as an explicit interface rather than inferred from the builder call, so the form's
 * contract is a thing that can be read, referenced and asserted against. `FormControl<string>`
 * — not `FormControl<string | null>` — because the builder is the non-nullable one; the
 * nullable default exists for `reset()`, which this form never calls, and it would otherwise
 * push a `| null` through every consumer of the value.
 */
interface SearchFilters {
  term: FormControl<string>;
  pageSize: FormControl<number>;
}

/** Offered page sizes. All at or below the server's cap of 100, so none is silently clamped. */
const PAGE_SIZE_OPTIONS = [10, 25, 50] as const;

@Component({
  selector: 'app-patient-search',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink],
  // Provided here, not in root: the store's lifetime is this screen's. A root-provided store
  // would keep the last search — patient names and all — in memory for the rest of the
  // session, and would show it again, stale, when the operator navigated back.
  providers: [PatientSearchStore],
  templateUrl: './patient-search.html',
  styleUrl: './patient-search.css',
})
export class PatientSearchPage {
  protected readonly store = inject(PatientSearchStore);
  protected readonly minQueryLength = MIN_QUERY_LENGTH;
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  protected readonly fullName = fullName;

  private readonly formBuilder = inject(NonNullableFormBuilder);

  protected readonly filters = this.formBuilder.group<SearchFilters>({
    term: this.formBuilder.control('', {
      // Validated for the *message*, not for control flow. The store refuses to issue a
      // short query regardless of what the form thinks, because validity is a rule about the
      // request and cannot live somewhere a second caller could bypass. What the validator
      // buys is `aria-invalid` and a described error, which the store cannot provide.
      validators: [Validators.minLength(MIN_QUERY_LENGTH)],
    }),
    pageSize: this.formBuilder.control(PAGE_SIZE_OPTIONS[1]),
  });

  /**
   * The rendered result rows, in order.
   *
   * A signal-based view query, so the focus effect below can depend on it and re-run when
   * the list changes.
   */
  private readonly resultRows = viewChildren<ElementRef<HTMLAnchorElement>>('resultRow');

  constructor() {
    /**
     * Form → store.
     *
     * A plain subscription rather than `toSignal` plus an `effect`. Reactive forms are an
     * RxJS API and this is a straight forwarding of events; wrapping it in a signal so that
     * an effect can unwrap it again adds a layer whose only purpose is to look uniform.
     * `effect` is also the wrong tool for propagating state — it is for synchronising with
     * something outside the reactive graph, which is precisely what the *other* effect in
     * this class does.
     *
     * The two controls are subscribed separately because they have different urgency: the
     * store debounces the term and deliberately does not debounce the page size.
     */
    this.filters.controls.term.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((term) => this.store.setTerm(term));

    this.filters.controls.pageSize.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((pageSize) => this.store.setPageSize(pageSize));

    /**
     * Move focus to the first newly appended row after "load more".
     *
     * This is the justified `effect`: synchronising the reactive graph with something it
     * does not own — the document's focus — which is a side effect by definition and cannot
     * be a `computed`.
     *
     * The problem it solves is specific. "Load more" appends rows *below* the button, so a
     * keyboard or screen-reader user who activates it stays on a button that has not moved
     * while content they cannot see appears behind them. Their next Tab goes past everything
     * that was just loaded. Moving focus to the first new row puts them at the start of the
     * new content, which is where they asked to be.
     *
     * It deliberately does **not** fire for a new search: replacing the list while someone
     * is typing and yanking focus out of the search box would make the box unusable. The
     * store distinguishes the two by setting `appendedFrom` only on an append.
     *
     * Both `appendedFrom()` and `resultRows()` are read, and that pairing is what makes it
     * correct rather than racy. When the store publishes the new index, the view may not
     * have rendered the rows yet and the lookup misses; the query signal then updates as the
     * rows render, the effect re-runs on that dependency, and the lookup succeeds. No
     * `setTimeout`, and no assumption about the order of change detection and DOM commit.
     */
    effect(() => {
      const firstNewIndex = this.store.appendedFrom();
      if (firstNewIndex < 0) {
        return;
      }
      this.resultRows()[firstNewIndex]?.nativeElement.focus();
    });
  }

  protected onLoadMore(): void {
    this.store.loadMore();
  }

  protected onRetry(): void {
    this.store.retry();
  }
}
