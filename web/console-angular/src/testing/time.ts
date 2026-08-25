import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';

/**
 * Deterministic time control for specs.
 *
 * ## Why not `fakeAsync` / `tick`
 *
 * Angular's `fakeAsync` is implemented by `zone-testing.js`: it installs a fake-async Zone
 * and patches timers through it. This application is **zoneless** — Angular 21's default,
 * and `zone.js` is not a dependency — so `fakeAsync` throws
 * "zone-testing.js is needed for the fakeAsync() test helper" rather than running.
 *
 * Adding `zone.js` back purely for tests would mean the test environment schedules work
 * differently from the environment the application actually runs in, which is precisely the
 * class of difference that produces a green suite and a broken screen.
 *
 * `vi.useFakeTimers()` provides the same guarantee `fakeAsync` does — virtual time, advanced
 * explicitly, no real waiting — at the layer below the framework. RxJS schedules
 * `debounceTime`, `timer` and `delay` on `setInterval`/`setTimeout`, so faking those is
 * sufficient to control every asynchronous operation in this codebase.
 *
 * `toFake` is listed explicitly rather than left to the default. The default fakes
 * `queueMicrotask` as well, and Angular schedules effect flushes onto microtasks — faking
 * those makes effects appear to never run and produces test failures whose cause is nowhere
 * near the assertion.
 */
export function useVirtualTime(): void {
  vi.useFakeTimers({
    toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'],
  });
}

export function useRealTime(): void {
  vi.useRealTimers();
}

/**
 * Advances virtual time and then flushes Angular's reactive work.
 *
 * Both halves are needed, in this order, and the order is not arbitrary. `TestBed.tick()`
 * runs pending effects — including the internal effect behind `toObservable`, which is how a
 * signal write reaches an RxJS pipeline. A test that only advances timers never delivers the
 * signal change into the stream; a test that only ticks never fires the debounce.
 */
export function advance(ms: number): void {
  vi.advanceTimersByTime(ms);
  TestBed.tick();
}

/**
 * Flushes the reactive graph without advancing time.
 *
 * Used immediately after writing a signal that an RxJS pipeline observes, to push the value
 * into the stream before the timers that act on it are advanced.
 */
export function flushReactive(): void {
  TestBed.tick();
}
