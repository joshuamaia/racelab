import { computed, signal } from '@angular/core';
import { ExperimentEvent } from '../../../core/models/api.models';

export const REPLAY_SPEEDS = [0.25, 0.5, 1, 2, 5, 10] as const;
export const REPLAY_MAX_STEP_MS = 1500;
export const REPLAY_MIN_STEP_MS = 25;

/** Replays stored events respecting their relative elapsed times (scaled by speed). */
export class ReplayController {
  readonly active = signal(false);
  readonly playing = signal(false);
  readonly position = signal(0);
  readonly speed = signal<number>(1);
  private source: readonly ExperimentEvent[] = [];
  private timer: ReturnType<typeof setTimeout> | null = null;

  readonly total = signal(0);
  readonly progress = computed(() => (this.total() ? this.position() / this.total() : 0));

  begin(events: readonly ExperimentEvent[]): void {
    this.stopTimer();
    this.source = [...events].sort((a, b) => a.elapsedTimeMs - b.elapsedTimeMs || a.sequence - b.sequence);
    this.total.set(this.source.length);
    this.position.set(0);
    this.active.set(true);
    this.play();
  }

  /** Visible slice during replay. */
  visible(): readonly ExperimentEvent[] {
    return this.source.slice(0, this.position());
  }

  play(): void {
    if (!this.active()) return;
    if (this.position() >= this.total()) this.position.set(0);
    this.playing.set(true);
    this.scheduleNext();
  }

  pause(): void {
    this.playing.set(false);
    this.stopTimer();
  }

  seek(position: number): void {
    this.position.set(Math.max(0, Math.min(this.total(), Math.round(position))));
    if (this.playing()) {
      this.stopTimer();
      this.scheduleNext();
    }
  }

  stepForward(): void {
    this.pause();
    this.seek(this.position() + 1);
  }

  exit(): void {
    this.stopTimer();
    this.playing.set(false);
    this.active.set(false);
    this.source = [];
    this.total.set(0);
    this.position.set(0);
  }

  private scheduleNext(): void {
    const pos = this.position();
    if (pos >= this.total()) {
      this.playing.set(false);
      return;
    }
    const prev = pos > 0 ? this.source[pos - 1].elapsedTimeMs : this.source[0].elapsedTimeMs;
    const delta = Math.max(0, this.source[pos].elapsedTimeMs - prev) / this.speed();
    const wait = pos === 0 ? 0 : Math.min(REPLAY_MAX_STEP_MS, Math.max(REPLAY_MIN_STEP_MS, delta));
    this.timer = setTimeout(() => {
      this.position.set(pos + 1);
      if (this.playing()) this.scheduleNext();
    }, wait);
  }

  private stopTimer(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }
}
