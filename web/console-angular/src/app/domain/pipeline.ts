/**
 * The operational view of the pipeline: which stage is up, and what it has done to the
 * messages it was handed.
 */

/**
 * Health as this console is prepared to render it.
 *
 * `unknown` is a first-class state and not an error. A probe that has not answered yet, and
 * a probe whose answer was "I am degraded", are different facts that call for different
 * operator behaviour; collapsing them into "red" trains people to ignore red. Likewise
 * `degraded` exists because most real outages in this pipeline are partial — the mapper is
 * alive and consuming but its Mongo writes are timing out — and a binary up/down light has
 * nowhere to put that.
 */
export type HealthState = 'healthy' | 'degraded' | 'down' | 'unknown';

/** Which stage of the pipeline a service occupies, in flow order. */
export type PipelineStage = 'ingest' | 'transform' | 'read' | 'claims' | 'edge';

/**
 * Message counters.
 *
 * Present on the services that own a message disposition and `null` on those that do not.
 * A gateway that answers a read query has no notion of "rejected", and reporting a zero for
 * it would show an operator a green zero that is indistinguishable from "nothing has been
 * rejected today" — which is a materially different statement.
 *
 * The three dispositions are kept separate because they demand different responses:
 *
 * - `accepted` — parsed, mapped and durably published.
 * - `rejected` — the message was understood and refused (unsupported ADT trigger, no patient
 *   identifier). Not retryable. Someone upstream is sending something this platform does not
 *   handle, and a rising count is a configuration conversation, not an incident.
 * - `failed`  — the message was acceptable but processing broke (broker unavailable, mapper
 *   exception). Retryable, and a rising count *is* an incident.
 *
 * Merging `rejected` into `failed` is the single most common mistake in a pipeline
 * dashboard: it puts a routine upstream misconfiguration on the same alert as a broker
 * outage, and the alert then gets muted.
 */
export interface MessageCounters {
  readonly accepted: number;
  readonly rejected: number;
  readonly failed: number;
}

export interface ServiceStatus {
  /** Stable machine id, used as the `@for` track key. */
  readonly id: string;
  readonly displayName: string;
  /** Implementation language, shown because this pipeline is deliberately polyglot. */
  readonly runtime: string;
  readonly stage: PipelineStage;
  readonly health: HealthState;
  /** One line of operator-facing detail; empty when there is nothing to add. */
  readonly detail: string;
  /** RFC 3339 timestamp of the probe that produced `health`. */
  readonly checkedAt: string;
  /** `null` for services that do not own a message disposition. */
  readonly counters: MessageCounters | null;
}

export interface PipelineStatus {
  readonly services: readonly ServiceStatus[];
  /** RFC 3339 timestamp of the snapshot as a whole. */
  readonly observedAt: string;
}

/** Flow order of the pipeline, used to lay the dashboard out left-to-right. */
export const STAGE_ORDER: readonly PipelineStage[] = [
  'ingest',
  'transform',
  'read',
  'edge',
  'claims',
];

/**
 * The worst health across the pipeline, which is what the header light shows.
 *
 * Worst-wins rather than an average: a pipeline with four healthy stages and one down stage
 * is not "80% healthy", it is not delivering admissions.
 */
export function worstHealth(services: readonly ServiceStatus[]): HealthState {
  const severity: Record<HealthState, number> = {
    healthy: 0,
    unknown: 1,
    degraded: 2,
    down: 3,
  };
  return services.reduce<HealthState>(
    (worst, service) => (severity[service.health] > severity[worst] ? service.health : worst),
    services.length === 0 ? 'unknown' : 'healthy',
  );
}
