import type { Routes } from '@angular/router';

/**
 * Application routes.
 *
 * ## What is, and is not, allowed to appear in a URL here
 *
 * The MRN is a path parameter on the detail route, and that is the only patient data in any
 * URL this console produces. In particular the search term is **not** a query parameter,
 * even though making it one would be free and would make searches shareable and
 * back-button-navigable.
 *
 * A search term is a patient name. URLs are recorded in places clinical data is not: the
 * browser's own history (which syncs across devices for a signed-in profile), proxy and
 * load-balancer access logs, the `Referer` header on any outbound link, crash reports, and
 * the address bar during a screen share or a recorded call. None of those sit inside the
 * retention, access-control and audit regime the clinical store does, and a name in a log
 * line stays there for as long as log retention does.
 *
 * The MRN is in the URL because a deep link to a patient is worth having and because an MRN
 * is meaningless outside the institution that issued it — it is an identifier, not a
 * demographic. That is a judgement about *degree* of disclosure, not an exemption from the
 * rule, which is why the tradeoff is written down rather than assumed.
 *
 * The cost is that search state does not survive a reload. That is accepted.
 *
 * ## Why every route is lazy
 *
 * Not for bundle size — this application is small enough that the whole thing would be a
 * rounding error over the framework. It is so that a route's dependencies are named at its
 * definition: `loadComponent` makes an accidental import from the pipeline dashboard into
 * patient search show up as a shared chunk in the build output, rather than as nothing at
 * all.
 */
export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'patients',
  },
  {
    path: 'patients',
    title: 'Patient search · Interop console',
    loadComponent: () =>
      import('./features/patient-search/patient-search').then((m) => m.PatientSearchPage),
  },
  {
    path: 'patients/:mrn',
    // No patient name in the title. Window titles reach the browser's history, the OS window
    // list, screen-share previews and session-recording tools — the same channels the URL
    // rule above is about.
    title: 'Patient record · Interop console',
    loadComponent: () =>
      import('./features/patient-detail/patient-detail').then((m) => m.PatientDetailPage),
  },
  {
    path: 'pipeline',
    title: 'Pipeline status · Interop console',
    loadComponent: () =>
      import('./features/pipeline-status/pipeline-status').then((m) => m.PipelineStatusPage),
  },
  {
    path: '**',
    title: 'Not found · Interop console',
    loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFoundPage),
  },
];
