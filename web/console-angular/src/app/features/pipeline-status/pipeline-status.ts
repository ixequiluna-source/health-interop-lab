import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { switchMap, timer } from 'rxjs';

import { ENVIRONMENT } from '../../core/environment';
import { GatewayError, PatientGateway } from '../../data/patient-gateway';
import {
  STAGE_ORDER,
  worstHealth,
  type HealthState,
  type PipelineStage,
} from '../../domain/pipeline';

const STAGE_LABELS: Readonly<Record<PipelineStage, string>> = {
  ingest: 'Ingest',
  transform: 'Transform',
  read: 'Read',
  edge: 'Edge',
  claims: 'Claims',
};

const HEALTH_LABELS: Readonly<Record<HealthState, string>> = {
  healthy: 'Healthy',
  degraded: 'Degraded',
  down: 'Down',
  unknown: 'Unknown',
};

@Component({
  selector: 'app-pipeline-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, DecimalPipe],
  templateUrl: './pipeline-status.html',
  styleUrl: './pipeline-status.css',
})
export class PipelineStatusPage {
  private readonly gateway = inject(PatientGateway);
  private readonly environment = inject(ENVIRONMENT);

  protected readonly pollSeconds = Math.round(this.environment.pipelinePollMs / 1000);

  /**
   * The polling snapshot.
   *
   * `timer(0, period).pipe(switchMap(…))` inside the resource's `stream`, rather than a
   * resource that is `reload()`ed from a `setInterval`. Three things fall out of that:
   *
   * - The polling stops when the component is destroyed, because the resource unsubscribes.
   *   An interval outside the reactive graph keeps firing against a dead component, and the
   *   symptom is a request every fifteen seconds for the rest of the session.
   * - `switchMap` drops a poll that is still in flight when the next tick arrives. Under a
   *   degraded backend — exactly when this page is being looked at — `mergeMap` semantics
   *   would pile requests onto a service that is already struggling, and could render an
   *   older snapshot over a newer one.
   * - The resource stays in `resolved` between ticks rather than flipping to `loading`, so
   *   the dashboard does not flash a skeleton every poll. Only the first load is a `loading`
   *   state, which is the one where there is genuinely nothing to show.
   */
  protected readonly statusResource = rxResource({
    stream: () =>
      timer(0, this.environment.pipelinePollMs).pipe(
        switchMap(() => this.gateway.pipelineStatus()),
      ),
  });

  /**
   * The services in the current snapshot, or none.
   *
   * Guarded with `hasValue()` because `ResourceRef.value()` throws when the resource is in
   * its error state rather than returning `undefined`. A `value()?.services ?? []` reads as
   * though it handles every case and in fact handles every case but the one that matters:
   * on a failed poll it throws inside a `computed`, during change detection, and the error
   * surfaces somewhere unrelated to the endpoint that actually failed.
   */
  protected readonly services = computed(() =>
    this.statusResource.hasValue() ? this.statusResource.value().services : [],
  );

  /**
   * Services in pipeline flow order, not in whatever order the endpoint returned them.
   *
   * The dashboard is read as a pipeline — a message enters at ingest and leaves at read —
   * and an operator scanning for where a backlog starts needs the stages laid out in that
   * order. Sorting server-side would work too, but it would make the ordering an undocumented
   * property of a response that has no other reason to be ordered.
   */
  protected readonly orderedServices = computed(() =>
    [...this.services()].sort(
      (a, b) => STAGE_ORDER.indexOf(a.stage) - STAGE_ORDER.indexOf(b.stage),
    ),
  );

  protected readonly overall = computed(() => worstHealth(this.services()));

  protected readonly error = computed(() => {
    const error = this.statusResource.error();
    return error instanceof GatewayError ? error : null;
  });

  /**
   * Total failures across the pipeline.
   *
   * Only `failed` is summed, and only across the services that report it. `rejected` is
   * deliberately excluded: a rejected message was understood and refused — an unsupported
   * ADT trigger, a message with no patient identifier — which is an upstream configuration
   * conversation, not an incident. Rolling the two together puts a sender's routine
   * misconfiguration on the same number as a broker outage, and the number then gets ignored.
   */
  protected readonly totalFailed = computed(() =>
    this.services().reduce((sum, service) => sum + (service.counters?.failed ?? 0), 0),
  );

  protected stageLabel(stage: PipelineStage): string {
    return STAGE_LABELS[stage];
  }

  protected healthLabel(health: HealthState): string {
    return HEALTH_LABELS[health];
  }

  protected onRetry(): void {
    this.statusResource.reload();
  }
}
