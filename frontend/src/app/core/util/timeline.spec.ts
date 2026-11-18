import { ev } from '../../../testing/fixtures';
import { eventStyle, eventTone } from './event-style';
import { EXPERIMENT_LANE_ID, buildLanes, buildRows, eventsToCsv, filterEvents, normalizeEvents } from './timeline';

const events = [
  ev(1, 'EXPERIMENT_STARTED', null, 0),
  ev(2, 'TRANSACTION_STARTED', 'worker-10', 5),
  ev(3, 'TRANSACTION_STARTED', 'worker-2', 5),
  ev(4, 'READ_COMPLETED', 'worker-2', 20),
  ev(5, 'LOCK_WAITING', 'worker-10', 30),
  ev(6, 'LOCK_ACQUIRED', 'worker-10', 80),
  ev(7, 'OPTIMISTIC_LOCK_CONFLICT', 'worker-2', 90, { source: 'DATABASE_OBSERVED' }),
  ev(8, 'COMMIT_COMPLETED', 'worker-10', 120),
  ev(9, 'EXPERIMENT_COMPLETED', null, 130),
];

describe('timeline lane building', () => {
  it('creates a header lane first and one lane per worker in natural order', () => {
    const lanes = buildLanes(events);
    expect(lanes.map((l) => l.id)).toEqual([EXPERIMENT_LANE_ID, 'worker-2', 'worker-10']);
    expect(lanes[0].kind).toBe('experiment');
    expect(lanes[1].transactionIds).toEqual(['tx-worker-2']);
    expect(lanes[2].eventCount).toBe(4);
  });

  it('orders rows by elapsed time with sequence tiebreak and time-proportional gaps', () => {
    const lanes = buildLanes(events);
    const rows = buildRows(events, lanes, 1);
    expect(rows.map((r) => r.event.sequence)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9]);
    expect(rows[1].laneIndex).toBe(2);
    expect(rows[2].laneIndex).toBe(1);
    expect(rows[3].deltaMs).toBe(15);
    expect(rows[3].gapPx).toBe(15);
    expect(buildRows(events, lanes, 0).every((r) => r.gapPx === 0)).toBe(true);
  });

  it('tracks lock waiting/holding per lane and flags conflicts', () => {
    const lanes = buildLanes(events);
    const rows = buildRows(events, lanes, 0.5);
    const w10 = 2;
    expect(rows.find((r) => r.event.sequence === 5)?.locks[w10]).toBe('waiting');
    expect(rows.find((r) => r.event.sequence === 6)?.locks[w10]).toBe('holding');
    expect(rows.find((r) => r.event.sequence === 8)?.locks[w10]).toBeNull();
    expect(rows.find((r) => r.event.sequence === 7)?.conflict).toBe(true);
  });

  it('filters by worker, transaction and type, keeping experiment-level events for worker filters', () => {
    expect(filterEvents(events, { workers: ['worker-2'] }).map((e) => e.sequence)).toEqual([1, 3, 4, 7, 9]);
    expect(filterEvents(events, { transactionIds: ['tx-worker-10'] }).map((e) => e.sequence)).toEqual([1, 2, 5, 6, 8, 9]);
    expect(filterEvents(events, { types: ['COMMIT_COMPLETED'] }).map((e) => e.sequence)).toEqual([8]);
  });

  it('normalizes: sorts by sequence and removes duplicates', () => {
    const out = normalizeEvents([events[3], events[0], events[3], events[1]]);
    expect(out.map((e) => e.sequence)).toEqual([1, 2, 4]);
  });

  it('exports CSV with escaping', () => {
    const csv = eventsToCsv([ev(1, 'READ_COMPLETED', 'worker-1', 3, { message: 'Read "1,000.00"' })]);
    const [header, line] = csv.split('\n');
    expect(header.startsWith('sequence,elapsedTimeMs')).toBe(true);
    expect(line).toContain('"Read ""1,000.00"""');
  });

  it('maps event types to tones with icons, and unknown types to grey', () => {
    expect(eventTone('READ_STARTED')).toBe('read');
    expect(eventTone('WRITE_COMPLETED')).toBe('write');
    expect(eventTone('CHECKPOINT_REACHED')).toBe('wait');
    expect(eventTone('DEADLOCK_DETECTED')).toBe('conflict');
    expect(eventTone('COMMIT_COMPLETED')).toBe('commit');
    expect(eventTone('ROLLBACK_COMPLETED')).toBe('rollback');
    expect(eventTone('DATA_PREPARED')).toBe('neutral');
    const unknown = eventStyle('SOMETHING_NEW' as never);
    expect(unknown.tone).toBe('neutral');
    expect(unknown.label).toBe('Something new');
    expect(unknown.icon).toBeTruthy();
  });
});
