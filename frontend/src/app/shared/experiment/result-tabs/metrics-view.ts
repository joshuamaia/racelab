import { EventType, ExperimentEvent, Metrics } from '../../../core/models/api.models';
import { formatMs, formatNumber } from '../../../core/util/format';

export interface MetricCard {
  label: string;
  value: string;
  icon: string;
  tone: string;
}

export function metricCards(m: Metrics): MetricCard[] {
  return [
    { label: 'Total time', value: formatMs(m.totalTimeMs), icon: 'pi pi-clock', tone: 'read' },
    { label: 'Avg latency', value: formatMs(m.avgLatencyMs, 1), icon: 'pi pi-stopwatch', tone: 'read' },
    { label: 'p95 latency', value: formatMs(m.p95LatencyMs), icon: 'pi pi-chart-line', tone: 'read' },
    { label: 'Throughput', value: `${formatNumber(m.throughputTps, 2)} tx/s`, icon: 'pi pi-bolt', tone: 'info' },
    { label: 'Committed', value: formatNumber(m.committed), icon: 'pi pi-check-circle', tone: 'commit' },
    { label: 'Rolled back', value: formatNumber(m.rolledBack), icon: 'pi pi-undo', tone: 'rollback' },
    { label: 'Business rejections', value: formatNumber(m.businessRejections), icon: 'pi pi-ban', tone: 'wait' },
    { label: 'Conflicts', value: formatNumber(m.conflicts), icon: 'pi pi-exclamation-triangle', tone: 'conflict' },
    { label: 'Deadlocks', value: formatNumber(m.deadlocks), icon: 'pi pi-sync', tone: 'conflict' },
    { label: 'Retries', value: formatNumber(m.retries), icon: 'pi pi-replay', tone: 'wait' },
    { label: 'Lock wait', value: formatMs(m.lockWaitTimeMs), icon: 'pi pi-hourglass', tone: 'wait' },
    { label: 'Inconsistencies', value: formatNumber(m.inconsistencies), icon: 'pi pi-exclamation-circle', tone: m.inconsistencies ? 'conflict' : 'commit' },
  ];
}

const LIVE_COUNTERS: { label: string; types: EventType[]; tone: string; icon: string }[] = [
  { label: 'Transactions started', types: ['TRANSACTION_STARTED'], tone: 'read', icon: 'pi pi-arrow-right' },
  { label: 'Commits', types: ['COMMIT_COMPLETED'], tone: 'commit', icon: 'pi pi-check-circle' },
  { label: 'Rollbacks', types: ['ROLLBACK_COMPLETED'], tone: 'rollback', icon: 'pi pi-undo' },
  { label: 'Lock waits', types: ['LOCK_WAITING'], tone: 'wait', icon: 'pi pi-hourglass' },
  {
    label: 'Conflicts',
    types: ['OPTIMISTIC_LOCK_CONFLICT', 'SERIALIZATION_FAILURE', 'DEADLOCK_DETECTED'],
    tone: 'conflict',
    icon: 'pi pi-exclamation-triangle',
  },
  { label: 'Retries', types: ['RETRY_SCHEDULED'], tone: 'wait', icon: 'pi pi-replay' },
];

/** Counts derived from the events received so far (shown while the result is not available yet). */
export function liveCounters(events: readonly ExperimentEvent[]): MetricCard[] {
  const counts = new Map<EventType, number>();
  for (const e of events) counts.set(e.eventType, (counts.get(e.eventType) ?? 0) + 1);
  return LIVE_COUNTERS.map((c) => ({
    label: c.label,
    value: formatNumber(c.types.reduce((s, t) => s + (counts.get(t) ?? 0), 0)),
    icon: c.icon,
    tone: c.tone,
  }));
}
