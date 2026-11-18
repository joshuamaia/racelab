import { Comparison, StrategyType } from '../../core/models/api.models';

export interface ComparisonRow {
  strategy: StrategyType;
  strategyName: string;
  measuredRuns: number;
  totalTimeMs: number;
  avgLatencyMs: number;
  p95LatencyMs: number;
  throughputTps: number;
  started: number;
  committed: number;
  rolledBack: number;
  businessRejections: number;
  conflicts: number;
  retries: number;
  lockWaitTimeMs: number;
  inconsistencies: number;
  consistentRuns: number;
  inconsistentRuns: number;
  consistencyPreserved: boolean;
  /** "label attribute: actual (expected X) OK|INVÁLIDA" per final state row of the last measured run. */
  finalState: string;
  finalStateOk: boolean;
  experimentIds: string[];
}

/** Maps comparison results (one per strategy) to table rows, in the order of the requested strategies. */
export function toComparisonRows(c: Comparison, strategyName: (s: StrategyType) => string): ComparisonRow[] {
  const order = new Map(c.strategies.map((s, i) => [s, i]));
  return [...(c.results ?? [])]
    .sort((a, b) => (order.get(a.strategy) ?? 99) - (order.get(b.strategy) ?? 99))
    .map((r) => {
      const m = r.metrics;
      const rows = r.finalStateRows ?? [];
      return {
        strategy: r.strategy,
        strategyName: strategyName(r.strategy),
        measuredRuns: r.measuredRuns,
        totalTimeMs: m.totalTimeMs,
        avgLatencyMs: m.avgLatencyMs,
        p95LatencyMs: m.p95LatencyMs,
        throughputTps: m.throughputTps,
        started: m.transactionsStarted,
        committed: m.committed,
        rolledBack: m.rolledBack,
        businessRejections: m.businessRejections,
        conflicts: m.conflicts,
        retries: m.retries,
        lockWaitTimeMs: m.lockWaitTimeMs,
        inconsistencies: m.inconsistencies,
        consistentRuns: r.consistentRuns,
        inconsistentRuns: r.inconsistentRuns,
        consistencyPreserved: r.consistencyPreserved,
        finalState: rows.length
          ? rows
              .map(
                (s) =>
                  `${s.label || s.resource} ${s.attribute}: ${s.actual ?? '—'} (expected ${s.expected ?? '—'}) ${s.ok ? 'OK' : 'INVÁLIDA'}`,
              )
              .join('; ')
          : '—',
        finalStateOk: rows.every((s) => s.ok),
        experimentIds: r.experimentIds ?? [],
      };
    });
}
