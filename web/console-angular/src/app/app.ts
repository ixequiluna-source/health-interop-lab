import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { ENVIRONMENT } from './core/environment';

/**
 * The application shell: masthead, primary navigation, routed outlet.
 *
 * `OnPush` here as everywhere else. It is the default in this codebase rather than an
 * optimisation applied where profiling suggested it, because the two strategies impose
 * different rules on how state is written and mixing them within one tree means neither set
 * of rules holds. With signals throughout, `OnPush` costs nothing to satisfy: every value a
 * template reads is a signal, so every read is tracked and every write marks the component
 * dirty without a `markForCheck` anywhere in the codebase.
 */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly environment = inject(ENVIRONMENT);
}
