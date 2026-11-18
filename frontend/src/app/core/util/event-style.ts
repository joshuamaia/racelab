import { EventType } from '../models/api.models';

export type EventTone = 'read' | 'write' | 'wait' | 'conflict' | 'commit' | 'rollback' | 'neutral';

export interface EventStyle {
  tone: EventTone;
  icon: string;
  label: string;
}

const CONFLICT_TYPES: ReadonlySet<EventType> = new Set<EventType>([
  'DEADLOCK_DETECTED',
  'OPTIMISTIC_LOCK_CONFLICT',
  'SERIALIZATION_FAILURE',
  'INCONSISTENCY_DETECTED',
]);

const LOCK_TYPES: ReadonlySet<EventType> = new Set<EventType>([
  'LOCK_REQUESTED',
  'LOCK_ACQUIRED',
  'LOCK_WAITING',
  'LOCK_RELEASED',
  'DEADLOCK_DETECTED',
]);

export const TERMINAL_EVENT_TYPES: ReadonlySet<EventType> = new Set<EventType>([
  'EXPERIMENT_COMPLETED',
  'EXPERIMENT_FAILED',
  'EXPERIMENT_CANCELLED',
]);

export function isConflictEvent(type: EventType): boolean {
  return CONFLICT_TYPES.has(type);
}

export function isLockEvent(type: EventType): boolean {
  return LOCK_TYPES.has(type);
}

/** Colour mapping from API-CONTRACT.md "Timeline colour mapping". Icons + labels so colour is never the only cue. */
export function eventTone(type: EventType): EventTone {
  if (typeof type !== 'string') return 'neutral'; // unknown / future types render as grey "other"
  if (type.startsWith('READ_')) return 'read';
  if (type.startsWith('WRITE_')) return 'write';
  if (type === 'LOCK_WAITING' || type === 'LOCK_REQUESTED' || type.startsWith('CHECKPOINT_')) return 'wait';
  if (CONFLICT_TYPES.has(type)) return 'conflict';
  if (type.startsWith('COMMIT_')) return 'commit';
  if (type.startsWith('ROLLBACK_')) return 'rollback';
  return 'neutral';
}

const ICONS: Partial<Record<EventType, string>> = {
  EXPERIMENT_CREATED: 'pi pi-plus-circle',
  EXPERIMENT_STARTED: 'pi pi-play',
  DATA_PREPARED: 'pi pi-database',
  TRANSACTION_STARTED: 'pi pi-arrow-right',
  READ_STARTED: 'pi pi-eye',
  READ_COMPLETED: 'pi pi-eye',
  VALIDATION_STARTED: 'pi pi-check-square',
  VALIDATION_COMPLETED: 'pi pi-check-square',
  LOCK_REQUESTED: 'pi pi-lock',
  LOCK_ACQUIRED: 'pi pi-lock',
  LOCK_WAITING: 'pi pi-hourglass',
  LOCK_RELEASED: 'pi pi-lock-open',
  WRITE_STARTED: 'pi pi-pencil',
  WRITE_COMPLETED: 'pi pi-pencil',
  COMMIT_STARTED: 'pi pi-check',
  COMMIT_COMPLETED: 'pi pi-check-circle',
  ROLLBACK_STARTED: 'pi pi-undo',
  ROLLBACK_COMPLETED: 'pi pi-times-circle',
  DEADLOCK_DETECTED: 'pi pi-sync',
  OPTIMISTIC_LOCK_CONFLICT: 'pi pi-exclamation-triangle',
  SERIALIZATION_FAILURE: 'pi pi-exclamation-triangle',
  RETRY_SCHEDULED: 'pi pi-replay',
  INCONSISTENCY_DETECTED: 'pi pi-exclamation-circle',
  CHECKPOINT_REACHED: 'pi pi-pause-circle',
  CHECKPOINT_RELEASED: 'pi pi-step-forward',
  MESSAGE_PUBLISHED: 'pi pi-send',
  MESSAGE_CONSUMED: 'pi pi-inbox',
  FAULT_INJECTED: 'pi pi-bolt',
  EXPERIMENT_COMPLETED: 'pi pi-flag-fill',
  EXPERIMENT_FAILED: 'pi pi-ban',
  EXPERIMENT_CANCELLED: 'pi pi-stop-circle',
};

export function eventLabel(type: EventType | string | null | undefined): string {
  const words = String(type ?? 'unknown').toLowerCase().split('_').filter((w) => w.length > 0);
  if (!words.length) return 'Unknown';
  return words.map((w, i) => (i === 0 ? w[0].toUpperCase() + w.slice(1) : w)).join(' ');
}

export function eventStyle(type: EventType): EventStyle {
  return { tone: eventTone(type), icon: ICONS[type] ?? 'pi pi-circle', label: eventLabel(type) };
}

export const TONE_LABELS: Record<EventTone, string> = {
  read: 'Read',
  write: 'Write',
  wait: 'Wait / lock request / checkpoint',
  conflict: 'Conflict / deadlock / inconsistency',
  commit: 'Commit',
  rollback: 'Rollback',
  neutral: 'Other',
};
