import { describe, expect, it } from 'vitest';

import { worstHealth, type ServiceStatus } from './pipeline';

function service(health: ServiceStatus['health']): ServiceStatus {
  return {
    id: `svc-${health}`,
    displayName: health,
    runtime: 'test',
    stage: 'ingest',
    health,
    detail: '',
    checkedAt: '2026-08-25T14:30:00.000Z',
    counters: null,
  };
}

describe('worstHealth', () => {
  it('is healthy only when every service is healthy', () => {
    expect(worstHealth([service('healthy'), service('healthy')])).toBe('healthy');
  });

  it('reports the worst state rather than the most common one', () => {
    // Worst-wins is the whole rule: a pipeline with four healthy stages and one down stage
    // is not "80% healthy", it is not delivering admissions.
    expect(worstHealth([service('healthy'), service('healthy'), service('down')])).toBe('down');
    expect(worstHealth([service('healthy'), service('degraded')])).toBe('degraded');
  });

  it('ranks down above degraded above unknown', () => {
    expect(worstHealth([service('unknown'), service('degraded')])).toBe('degraded');
    expect(worstHealth([service('degraded'), service('down')])).toBe('down');
  });

  it('treats unknown as worse than healthy but better than degraded', () => {
    // A probe that has not answered is not the same as a probe that answered "degraded".
    // Ranking unknown alongside down trains operators to ignore the alert; ranking it
    // alongside healthy hides a service nobody can see.
    expect(worstHealth([service('healthy'), service('unknown')])).toBe('unknown');
    expect(worstHealth([service('unknown'), service('degraded')])).toBe('degraded');
  });

  it('is unknown, not healthy, when there is nothing to report', () => {
    // An empty snapshot means the dashboard has no information, which must not render as an
    // all-clear.
    expect(worstHealth([])).toBe('unknown');
  });
});
