import { EventType, ExperimentEvent } from '../models/api.models';
import { isConflictEvent } from './event-style';

export const EXPERIMENT_LANE_ID = '__experiment__';

export interface TimelineLane {
  id: string;
  label: string;
  kind: 'experiment' | 'worker';
  instanceIds: string[];
  transactionIds: string[];
  eventCount: number;
}

export type LockMark = 'holding' | 'waiting' | null;

export interface TimelineRow {
  event: ExperimentEvent;
  laneIndex: number;
  /** ms since the previous visible event. */
  deltaMs: number;
  /** vertical spacing before the row, proportional to deltaMs × zoom (clamped). */
  gapPx: number;
  /** lock state of every lane after this event (index = lane index). */
  locks: LockMark[];
  conflict: boolean;
}

export interface TimelineFilter {
  workers?: readonly string[] | null;
  transactionIds?: readonly string[] | null;
  types?: readonly EventType[] | null;
}

export const MAX_GAP_PX = 240;

export function laneIdOf(event: ExperimentEvent): string {
  return event.workerId ?? EXPERIMENT_LANE_ID;
}

/** Natural comparison so that worker-2 < worker-10. */
export function naturalCompare(a: string, b: string): number {
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
}

/** Sorts by sequence (authoritative order) and removes duplicates by sequence. */
export function normalizeEvents(events: readonly ExperimentEvent[]): ExperimentEvent[] {
  const bySeq = new Map<number, ExperimentEvent>();
  for (const e of events) {
    if (!bySeq.has(e.sequence)) bySeq.set(e.sequence, e);
  }
  return [...bySeq.values()].sort((a, b) => a.sequence - b.sequence);
}

export function filterEvents(events: readonly ExperimentEvent[], filter: TimelineFilter): ExperimentEvent[] {
  const workers = filter.workers?.length ? new Set(filter.workers) : null;
  const txs = filter.transactionIds?.length ? new Set(filter.transactionIds) : null;
  const types = filter.types?.length ? new Set(filter.types) : null;
  return events.filter((e) => {
    if (types && !types.has(e.eventType)) return false;
    const isExperimentLevel = !e.workerId;
    if (workers && !isExperimentLevel && !workers.has(e.workerId as string)) return false;
    if (txs && !isExperimentLevel && (!e.transactionId || !txs.has(e.transactionId))) return false;
    return true;
  });
}

/**
 * One lane per worker (like a concurrency simulator) plus a header lane for experiment-level events
 * (events without workerId). The header lane is always first; worker lanes use natural ordering.
 */
export function buildLanes(events: readonly ExperimentEvent[]): TimelineLane[] {
  const map = new Map<string, TimelineLane>();
  for (const e of events) {
    const id = laneIdOf(e);
    let lane = map.get(id);
    if (!lane) {
      lane = {
        id,
        label: id === EXPERIMENT_LANE_ID ? 'Experiment' : id,
        kind: id === EXPERIMENT_LANE_ID ? 'experiment' : 'worker',
        instanceIds: [],
        transactionIds: [],
        eventCount: 0,
      };
      map.set(id, lane);
    }
    lane.eventCount++;
    if (e.transactionId && !lane.transactionIds.includes(e.transactionId)) lane.transactionIds.push(e.transactionId);
    if (e.instanceId && !lane.instanceIds.includes(e.instanceId)) lane.instanceIds.push(e.instanceId);
  }
  const lanes = [...map.values()];
  lanes.sort((a, b) => {
    if (a.kind !== b.kind) return a.kind === 'experiment' ? -1 : 1;
    return naturalCompare(a.id, b.id);
  });
  return lanes;
}

function nextLockMark(current: LockMark, type: EventType): LockMark {
  switch (type) {
    case 'LOCK_REQUESTED':
    case 'LOCK_WAITING':
      return current === 'holding' && type === 'LOCK_REQUESTED' ? 'holding' : 'waiting';
    case 'LOCK_ACQUIRED':
      return 'holding';
    case 'LOCK_RELEASED':
    case 'COMMIT_COMPLETED':
    case 'ROLLBACK_COMPLETED':
    case 'DEADLOCK_DETECTED':
      return type === 'DEADLOCK_DETECTED' ? current : null;
    default:
      return current;
  }
}

/** Builds rows ordered by elapsed time (ties broken by sequence) with time-proportional gaps. */
export function buildRows(
  events: readonly ExperimentEvent[],
  lanes: readonly TimelineLane[],
  zoomPxPerMs: number,
): TimelineRow[] {
  const index = new Map(lanes.map((l, i) => [l.id, i]));
  const ordered = [...events].sort((a, b) => a.elapsedTimeMs - b.elapsedTimeMs || a.sequence - b.sequence);
  const lockState: LockMark[] = lanes.map(() => null);
  const rows: TimelineRow[] = [];
  let prevMs: number | null = null;
  for (const event of ordered) {
    const laneIndex = index.get(laneIdOf(event));
    if (laneIndex === undefined) continue;
    const deltaMs = prevMs === null ? 0 : Math.max(0, event.elapsedTimeMs - prevMs);
    prevMs = event.elapsedTimeMs;
    lockState[laneIndex] = nextLockMark(lockState[laneIndex], event.eventType);
    rows.push({
      event,
      laneIndex,
      deltaMs,
      gapPx: Math.min(MAX_GAP_PX, Math.round(deltaMs * zoomPxPerMs)),
      locks: [...lockState],
      conflict: isConflictEvent(event.eventType),
    });
  }
  return rows;
}

// ---- Client-side export ---------------------------------------------------

const CSV_COLUMNS: (keyof ExperimentEvent)[] = [
  'sequence',
  'elapsedTimeMs',
  'timestamp',
  'eventType',
  'workerId',
  'transactionId',
  'threadName',
  'instanceId',
  'connectionPid',
  'resource',
  'source',
  'message',
  'correlationId',
  'details',
];

export function csvEscape(value: unknown): string {
  if (value === null || value === undefined) return '';
  const s = typeof value === 'object' ? JSON.stringify(value) : String(value);
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export function eventsToCsv(events: readonly ExperimentEvent[]): string {
  const header = CSV_COLUMNS.join(',');
  const lines = events.map((e) => CSV_COLUMNS.map((c) => csvEscape(e[c])).join(','));
  return [header, ...lines].join('\n');
}
