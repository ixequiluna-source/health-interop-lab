import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  ControllableGateway,
  gatewayError,
  samplePage,
  samplePatient,
} from '../../../testing/controllable-gateway';
import { advance, flushReactive, useRealTime, useVirtualTime } from '../../../testing/time';
import { PatientGateway } from '../../data/patient-gateway';
import { PatientSearchPage } from './patient-search';
import { PatientSearchStore, SEARCH_DEBOUNCE_MS } from './patient-search-store';

describe('PatientSearchPage', () => {
  let fixture: ComponentFixture<PatientSearchPage>;
  let gateway: ControllableGateway;
  let store: PatientSearchStore;

  beforeEach(() => {
    useVirtualTime();
    gateway = new ControllableGateway();

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PatientGateway, useValue: gateway },
      ],
    });

    fixture = TestBed.createComponent(PatientSearchPage);
    // The store is provided by the component, so it is resolved from the component's own
    // injector rather than the root one.
    store = fixture.debugElement.injector.get(PatientSearchStore);
    fixture.detectChanges();
    advance(SEARCH_DEBOUNCE_MS);
  });

  afterEach(() => {
    useRealTime();
  });

  function render(): HTMLElement {
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function search(term: string): void {
    store.setTerm(term);
    flushReactive();
    advance(SEARCH_DEBOUNCE_MS);
  }

  describe('rendering states', () => {
    it('starts with a prompt and no results', () => {
      const element = render();
      expect(element.textContent).toContain('Start typing to search');
      expect(element.querySelectorAll('a.result')).toHaveLength(0);
    });

    it('explains the minimum length rather than showing an empty result set', () => {
      search('g');
      const element = render();

      // "No patients matched" for a one-character search would be false: nothing was
      // searched for.
      expect(element.textContent).toContain('Enter at least 2 characters');
      expect(element.textContent).not.toContain('No patients matched');
    });

    it('shows skeleton rows while the first page is in flight', () => {
      search('garcia');
      const element = render();

      const skeletons = element.querySelectorAll('.result--skeleton');
      expect(skeletons.length).toBeGreaterThan(0);
      // Hidden from assistive technology: the live region already says "Searching", and
      // announcing five empty rows on top of that is noise.
      expect(element.querySelector('.results__list')?.getAttribute('aria-hidden')).toBe('true');
    });

    it('renders results as navigable links once they arrive', () => {
      search('garcia');
      gateway.searchCalls[0]?.resolve(
        samplePage({
          patients: [
            samplePatient({
              medicalRecordNumber: 'MRN-1',
              givenName: 'María',
              familyName: 'García',
            }),
            samplePatient({
              medicalRecordNumber: 'MRN-2',
              givenName: 'José',
              familyName: 'García',
            }),
          ],
          totalMatched: 2,
        }),
      );

      const element = render();
      const rows = element.querySelectorAll<HTMLAnchorElement>('a.result');

      expect(rows).toHaveLength(2);
      expect(rows[0]?.textContent).toContain('María García');
      // Each row is a real link, so it is keyboard-operable and openable in a new tab
      // without any custom key handling.
      expect(rows[0]?.getAttribute('href')).toBe('/patients/MRN-1');
    });

    it('shows an empty state naming what was searched for', () => {
      search('zzzz');
      gateway.searchCalls[0]?.resolve(samplePage({ patients: [], totalMatched: 0 }));

      const element = render();
      expect(element.textContent).toContain('No patients matched');
      expect(element.textContent).toContain('zzzz');
    });

    it('shows an error with the trace id and a retry control', () => {
      search('garcia');
      gateway.searchCalls[0]?.fail(gatewayError('unavailable', 'gateway draining'));

      const element = render();
      const alert = element.querySelector('[role="alert"]');

      expect(alert?.textContent).toContain('not answering');
      expect(alert?.textContent).toContain('gateway draining');
      // The trace id on screen is the whole point of the interceptor: a bug report becomes
      // an id the backend team can look up rather than a timestamp.
      expect(alert?.textContent).toContain('a1b2c3d4e5f60718293a4b5c6d7e8f90');
      expect(alert?.querySelector('button')).not.toBeNull();
    });

    it('offers no retry for a request that can never succeed', () => {
      // A stale page token or a too-short query will fail identically forever; a retry
      // button there implies the operator did something wrong by not pressing it enough.
      search('garcia');
      gateway.searchCalls[0]?.fail(gatewayError('invalid-request', 'page token is not valid'));

      const element = render();
      expect(element.querySelector('[role="alert"] button')).toBeNull();
    });

    it('keeps results on screen while a further page loads', () => {
      search('garcia');
      gateway.searchCalls[0]?.resolve(
        samplePage({
          patients: [samplePatient({ medicalRecordNumber: 'MRN-1' })],
          nextPageToken: 'token',
          totalMatched: 40,
        }),
      );
      render();

      const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
        '.results__more button',
      );
      button?.click();
      flushReactive();

      const element = render();
      expect(element.querySelectorAll('a.result')).toHaveLength(1);
      expect(element.querySelector('.results__more button')?.textContent).toContain('Loading');
      expect(element.querySelector<HTMLButtonElement>('.results__more button')?.disabled).toBe(
        true,
      );
    });

    it('hides the load-more control once there is no next page', () => {
      search('garcia');
      gateway.searchCalls[0]?.resolve(
        samplePage({ patients: [samplePatient()], nextPageToken: null, totalMatched: 1 }),
      );

      expect(render().querySelector('.results__more')).toBeNull();
    });
  });

  describe('the typed form', () => {
    it('drives the search from the input element', () => {
      // Proves the form is actually wired to the store, rather than the store being driven
      // directly by every other test in this file.
      const element = render();
      const input = element.querySelector<HTMLInputElement>('#search-term');
      expect(input).not.toBeNull();

      input!.value = 'garcia';
      input!.dispatchEvent(new Event('input'));
      flushReactive();
      advance(SEARCH_DEBOUNCE_MS);

      expect(gateway.searchCalls[0]?.request).toMatchObject({ query: 'garcia' });
    });

    it('sends the selected page size as a number, not as the DOM string', () => {
      // `<option [value]>` would write "10" into a `FormControl<number>`; the failure only
      // appears when the server rejects `"pageSize": "10"`.
      const element = render();
      const select = element.querySelector<HTMLSelectElement>('#page-size');
      expect(select).not.toBeNull();

      select!.selectedIndex = 0;
      select!.dispatchEvent(new Event('change'));
      flushReactive();

      store.setTerm('garcia');
      flushReactive();
      advance(SEARCH_DEBOUNCE_MS);

      expect(gateway.searchCalls.at(-1)?.request).toMatchObject({ pageSize: 10 });
      expect(typeof (gateway.searchCalls.at(-1)?.request as { pageSize: unknown }).pageSize).toBe(
        'number',
      );
    });
  });

  describe('accessibility', () => {
    it('associates a visible label and a persistent hint with the search box', () => {
      const element = render();
      const input = element.querySelector<HTMLInputElement>('#search-term');
      const label = element.querySelector<HTMLLabelElement>('label[for="search-term"]');

      expect(label?.textContent?.trim()).toBe('Name or identifier');
      // A placeholder is not a label: it disappears on the first keystroke and several
      // screen readers do not announce it at all.
      expect(input?.getAttribute('aria-describedby')).toBe('search-term-hint');
      expect(element.querySelector('#search-term-hint')).not.toBeNull();
    });

    it('announces the result count through a polite live region', () => {
      search('garcia');
      gateway.searchCalls[0]?.resolve(
        samplePage({
          patients: [samplePatient()],
          nextPageToken: 'more',
          totalMatched: 340,
        }),
      );

      const element = render();
      const live = element.querySelector('[role="status"]');

      // `role="status"` carries an implicit `aria-live="polite"`, which is required here:
      // the region changes on every debounce tick and an assertive one would cut the
      // operator off mid-word as they type.
      expect(live).not.toBeNull();
      expect(live?.textContent?.trim()).toBe('Showing 1 of 340 matched patients.');
    });

    it('keeps the live region mounted before it has anything to say', () => {
      // A live region added to the page at the same moment it gains content is frequently
      // not announced: the assistive technology has to be observing the node before the
      // mutation happens.
      const element = render();
      expect(element.querySelector('[role="status"]')).not.toBeNull();
      expect(element.querySelector('[role="status"]')?.textContent?.trim()).toBe('');
    });

    it('marks the search box invalid while the term is too short', () => {
      const element = render();
      const input = element.querySelector<HTMLInputElement>('#search-term');

      input!.value = 'g';
      input!.dispatchEvent(new Event('input'));

      expect(render().querySelector('#search-term')?.getAttribute('aria-invalid')).toBe('true');

      input!.value = 'ga';
      input!.dispatchEvent(new Event('input'));

      // Removed rather than set to "false": the attribute's absence is the correct
      // representation of "not invalid" and avoids an announcement on every keystroke.
      expect(render().querySelector('#search-term')?.hasAttribute('aria-invalid')).toBe(false);
    });

    it('moves focus to the first appended row after loading more', () => {
      // The justified `effect`. "Load more" appends below the button, so without this a
      // keyboard user's next Tab skips everything that was just loaded.
      search('garcia');
      gateway.searchCalls[0]?.resolve(
        samplePage({
          patients: [samplePatient({ medicalRecordNumber: 'MRN-1' })],
          nextPageToken: 'token',
          totalMatched: 2,
        }),
      );
      render();

      (fixture.nativeElement as HTMLElement)
        .querySelector<HTMLButtonElement>('.results__more button')
        ?.click();
      flushReactive();
      gateway.searchCalls[1]?.resolve(
        samplePage({
          patients: [samplePatient({ medicalRecordNumber: 'MRN-2' })],
          nextPageToken: null,
          totalMatched: 2,
        }),
      );

      const element = render();
      const rows = element.querySelectorAll<HTMLAnchorElement>('a.result');
      expect(rows).toHaveLength(2);
      expect(document.activeElement).toBe(rows[1]);
    });

    it('does not steal focus on a new search', () => {
      // Yanking focus out of the search box while someone is typing would make the box
      // unusable, which is why the store distinguishes an append from a replacement.
      const element = render();
      const input = element.querySelector<HTMLInputElement>('#search-term');
      input!.focus();

      search('garcia');
      gateway.searchCalls[0]?.resolve(samplePage({ patients: [samplePatient()], totalMatched: 1 }));
      render();

      expect(document.activeElement).toBe(input);
    });
  });
});
