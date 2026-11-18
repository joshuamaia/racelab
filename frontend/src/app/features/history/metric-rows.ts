import { Metrics } from '../../core/models/api.models';
import { formatMs, formatNumber } from '../../core/util/format';

export interface MetricRow {
  label: string;
  format: (m: Metrics) => string;
}

/** Rows of the side-by-side metrics comparison dialog. */
export const METRIC_ROWS: readonly MetricRow[] = [
  { label: 'Total time', format: (m) => formatMs(m.totalTimeMs) },
  { label: 'Avg latency', format: (m) => formatMs(m.avgLatencyMs, 1) },
  { label: 'p50 latency', format: (m) => formatMs(m.p50LatencyMs) },
  { label: 'p95 latency', format: (m) => formatMs(m.p95LatencyMs) },
  { label: 'Max latency', format: (m) => formatMs(m.maxLatencyMs) },
  { label: 'Throughput (tx/s)', format: (m) => formatNumber(m.throughputTps, 2) },
  { label: 'Transactions started', format: (m) => formatNumber(m.transactionsStarted) },
  { label: 'Attempts', format: (m) => formatNumber(m.attempts) },
  { label: 'Committed', format: (m) => formatNumber(m.committed) },
  { label: 'Rolled back', format: (m) => formatNumber(m.rolledBack) },
  { label: 'Business rejections', format: (m) => formatNumber(m.businessRejections) },
  { label: 'Conflicts', format: (m) => formatNumber(m.conflicts) },
  { label: 'Deadlocks', format: (m) => formatNumber(m.deadlocks) },
  { label: 'Serialization failures', format: (m) => formatNumber(m.serializationFailures) },
  { label: 'Optimistic conflicts', format: (m) => formatNumber(m.optimisticConflicts) },
  { label: 'Unique violations', format: (m) => formatNumber(m.uniqueViolations) },
  { label: 'Lock timeouts', format: (m) => formatNumber(m.lockTimeouts) },
  { label: 'Retries', format: (m) => formatNumber(m.retries) },
  { label: 'Lock wait', format: (m) => formatMs(m.lockWaitTimeMs) },
  { label: 'Inconsistencies', format: (m) => formatNumber(m.inconsistencies) },
];
