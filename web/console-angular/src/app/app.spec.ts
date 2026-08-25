import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { App } from './app';
import { ENVIRONMENT, type AppEnvironment } from './core/environment';

const TEST_ENVIRONMENT: AppEnvironment = {
  label: 'staging · http',
  gateway: 'http',
  gatewayBaseUrl: '',
  pipelinePollMs: 30_000,
};

describe('App shell', () => {
  let fixture: ComponentFixture<App>;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ENVIRONMENT, useValue: TEST_ENVIRONMENT },
      ],
    });
    fixture = TestBed.createComponent(App);
    await fixture.whenStable();
  });

  function element(): HTMLElement {
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows which data source the build is wired to', () => {
    // On screen at all times so a screenshot in a bug report is self-identifying: "it showed
    // the wrong patient" is a different conversation when the corner says `local · in-memory`.
    expect(element().querySelector('.masthead__env')?.textContent).toContain('staging · http');
  });

  it('puts a skip link first in the tab order, pointing at the main landmark', () => {
    const root = element();
    const skip = root.querySelector<HTMLAnchorElement>('a.skip-link');

    // Every route puts a filter bar above its content, so without this a keyboard user tabs
    // through the masthead and navigation on every single navigation.
    expect(skip).not.toBeNull();
    expect(root.firstElementChild?.classList.contains('skip-link')).toBe(true);
    expect(skip?.getAttribute('href')).toBe('#main');
    expect(root.querySelector('#main')).not.toBeNull();
  });

  it('makes the main landmark programmatically focusable but keeps it out of the tab order', () => {
    // Without `tabindex="-1"`, some browsers move the viewport on a skip-link activation but
    // leave focus in the header, and the next Tab lands back in the navigation.
    expect(element().querySelector('#main')?.getAttribute('tabindex')).toBe('-1');
  });

  it('names its landmarks', () => {
    const root = element();
    expect(root.querySelector('nav')?.getAttribute('aria-label')).toBe('Primary');
    expect(root.querySelector('main')).not.toBeNull();
    expect(root.querySelector('header')).not.toBeNull();
  });
});
