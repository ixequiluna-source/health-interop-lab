import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * The wildcard route.
 *
 * Deliberately says nothing about *why* the URL did not match, and in particular does not
 * echo the requested path back onto the page. An unmatched URL is attacker-controlled text,
 * and reflecting it is the reflected-XSS shape — Angular's interpolation escapes it, but the
 * page also gains nothing from showing it. It is a route that does not exist; the operator
 * knows which link they clicked.
 */
@Component({
  selector: 'app-not-found',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <h1 class="title">Page not found</h1>
    <p class="lede">That address does not correspond to a screen in this console.</p>
    <a class="button" routerLink="/patients">Go to patient search</a>
  `,
  styles: `
    :host {
      display: block;
      max-width: 32rem;
    }

    .title {
      font-size: 1.5rem;
    }

    .lede {
      margin: var(--space-2) 0 var(--space-5);
      color: var(--text-secondary);
    }

    .button {
      display: inline-block;
      padding: var(--space-2) var(--space-4);
      background: var(--accent);
      color: var(--text-inverse);
      border-radius: var(--radius-sm);
      font-weight: 500;
      text-decoration: none;
    }

    .button:hover {
      background: var(--accent-hover);
    }
  `,
})
export class NotFoundPage {}
