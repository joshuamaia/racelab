import { Comparison, Metrics } from '../../core/models/api.models';
import { toComparisonRows } from './comparison-table';

const metrics = (over: Partial<Metrics> = {}): Metrics => ({
  totalTimeMs: 100,
  avgLatencyMs: 50,
  p50LatencyMs: 40,
  p95LatencyMs: 90,
  maxLatencyMs: 95,
  throughputTps: 20,
  transactionsStarted: 2,
  attempts: 2,
  committed: 2,
  rolledBack: 0,
  businessRejections: 0,
  conflicts: 0,
  deadlocks: 0,
  serializationFailures: 0,
  optimisticConflicts: 0,
  uniqueViolations: 0,
  lockTimeouts: 0,
  retries: 0,
  lockWaitTimeMs: 0,
  inconsistencies: 0,
  ...over,
});

const comparison: Comparison = {
  id: 'CMP-1',
  name: null,
  scenarioId: 'bank-withdrawal',
  status: 'COMPLETED',
  strategies: ['NONE', 'OPTIMISTIC_LOCKING'],
  configuration: {},
  repetitions: 3,
  warmupRuns: 1,
  progress: { completedRuns: 8, totalRuns: 8, currentStrategy: null },
  createdAt: '2026-01-01T00:00:00Z',
  finishedAt: '2026-01-01T00:00:05Z',
  disclaimer: 'Results depend on hardware',
  results: [
    {
      strategy: 'OPTIMISTIC_LOCKING',
      experimentIds: ['EXP-2'],
      measuredRuns: 3,
      metrics: metrics({ conflicts: 1, retries: 1, rolledBack: 1, committed: 1 }),
      consistentRuns: 3,
      inconsistentRuns: 0,
      consistencyPreserved: true,
      finalStateRows: [
        { resource: 'account:ACC-A', label: 'Conta A', attribute: 'balance', initial: '1000.00', actual: '200.00', expected: '200.00', ok: true },
      ],
    },
    {
      strategy: 'NONE',
      experimentIds: ['EXP-1'],
      measuredRuns: 3,
      metrics: metrics({ inconsistencies: 1 }),
      consistentRuns: 0,
      inconsistentRuns: 3,
      consistencyPreserved: false,
      finalStateRows: [
        { resource: 'account:ACC-A', label: 'Conta A', attribute: 'balance', initial: '1000.00', actual: '200.00', expected: '-600.00', ok: false },
      ],
    },
  ],
};

describe('toComparisonRows', () => {
  const rows = toComparisonRows(comparison, (s) => `name:${s}`);

  it('keeps the order of the requested strategies', () => {
    expect(rows.map((r) => r.strategy)).toEqual(['NONE', 'OPTIMISTIC_LOCKING']);
  });

  it('maps metrics and names', () => {
    const opt = rows[1];
    expect(opt.strategyName).toBe('name:OPTIMISTIC_LOCKING');
    expect(opt.conflicts).toBe(1);
    expect(opt.retries).toBe(1);
    expect(opt.started).toBe(2);
    expect(opt.p95LatencyMs).toBe(90);
    expect(opt.experimentIds).toEqual(['EXP-2']);
  });

  it('summarises the final state with OK / INVÁLIDA', () => {
    expect(rows[0].finalStateOk).toBe(false);
    expect(rows[0].finalState).toContain('INVÁLIDA');
    expect(rows[0].consistencyPreserved).toBe(false);
    expect(rows[1].finalStateOk).toBe(true);
    expect(rows[1].finalState).toContain('OK');
  });

  it('handles missing results', () => {
    expect(toComparisonRows({ ...comparison, results: [] }, (s) => s)).toEqual([]);
  });
});
