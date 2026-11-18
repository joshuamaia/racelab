import { computed, signal } from '@angular/core';
import {
  ExperimentDetails,
  ExperimentEvent,
  ExperimentResult,
  ExperimentStatus,
  TERMINAL_STATUSES,
} from '../models/api.models';
import { ConnectionState } from '../sse/sse.service';
import { buildLanes } from '../util/timeline';

const STATUS_BY_TERMINAL_EVENT: Partial<Record<ExperimentEvent['eventType'], ExperimentStatus>> = {
  EXPERIMENT_STARTED: 'RUNNING',
  EXPERIMENT_COMPLETED: 'COMPLETED',
  EXPERIMENT_FAILED: 'FAILED',
  EXPERIMENT_CANCELLED: 'CANCELLED',
};

/** Signal-based state of one experiment: details, ordered/deduplicated events, result and derived lanes. */
export class ExperimentStore {
  readonly details = signal<ExperimentDetails | null>(null);
  readonly events = signal<ExperimentEvent[]>([]);
  readonly result = signal<ExperimentResult | null>(null);
  readonly resultLoading = signal(false);
  readonly connection = signal<ConnectionState>('idle');
  readonly reconnectAttempt = signal(0);
  readonly lastHeartbeat = signal<Date | null>(null);

  readonly id = computed(() => this.details()?.id ?? null);
  readonly status = computed<ExperimentStatus | null>(() => this.details()?.status ?? null);
  readonly stepState = computed(() => {
    const d = this.details();
    return d && d.status === 'RUNNING' ? d.stepState : null;
  });
  readonly isTerminal = computed(() => {
    const s = this.status();
    return s !== null && TERMINAL_STATUSES.includes(s);
  });
  readonly isActive = computed(() => this.status() === 'RUNNING' || this.status() === 'CREATED');
  readonly isStepMode = computed(() => this.details()?.executionMode === 'STEP_BY_STEP');
  readonly lanes = computed(() => buildLanes(this.events()));
  readonly lastSequence = computed(() => {
    const list = this.events();
    return list.length ? list[list.length - 1].sequence : null;
  });

  private readonly seen = new Set<number>();

  reset(details: ExperimentDetails | null = null): void {
    this.seen.clear();
    this.details.set(details);
    this.events.set([]);
    this.result.set(null);
    this.resultLoading.set(false);
    this.connection.set('idle');
    this.reconnectAttempt.set(0);
    this.lastHeartbeat.set(null);
  }

  setDetails(details: ExperimentDetails): void {
    const current = this.details();
    // never move a terminal status back to RUNNING because of a stale response
    if (current && current.id === details.id && TERMINAL_STATUSES.includes(current.status) && !TERMINAL_STATUSES.includes(details.status)) {
      this.details.set({ ...details, status: current.status, stepState: null });
      return;
    }
    this.details.set(details);
  }

  /** Adds events, deduplicating by sequence and keeping them ordered. Returns the number of new events. */
  addEvents(incoming: readonly ExperimentEvent[]): number {
    const fresh = incoming.filter((e) => {
      if (this.seen.has(e.sequence)) return false;
      this.seen.add(e.sequence);
      return true;
    });
    if (!fresh.length) return 0;
    this.events.update((list) => {
      const last = list.length ? list[list.length - 1].sequence : -Infinity;
      const sortedFresh = [...fresh].sort((a, b) => a.sequence - b.sequence);
      if (sortedFresh[0].sequence > last) return [...list, ...sortedFresh];
      return [...list, ...sortedFresh].sort((a, b) => a.sequence - b.sequence);
    });
    for (const e of fresh) this.applyStatusFromEvent(e);
    return fresh.length;
  }

  setStatus(status: ExperimentStatus): void {
    const d = this.details();
    if (!d || d.status === status) return;
    if (TERMINAL_STATUSES.includes(d.status) && !TERMINAL_STATUSES.includes(status)) return;
    this.details.set({ ...d, status, stepState: TERMINAL_STATUSES.includes(status) ? null : d.stepState });
  }

  private applyStatusFromEvent(e: ExperimentEvent): void {
    const status = STATUS_BY_TERMINAL_EVENT[e.eventType];
    if (status) this.setStatus(status);
  }
}
