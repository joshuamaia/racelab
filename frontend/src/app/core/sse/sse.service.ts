import { Injectable, InjectionToken, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ExperimentEvent, ExperimentStatus } from '../models/api.models';

/** Minimal subset of the EventSource API used by the service (allows mocking in tests). */
export interface EventSourceLike {
  readonly readyState: number;
  onopen: ((ev: Event) => unknown) | null;
  onerror: ((ev: Event) => unknown) | null;
  addEventListener(type: string, listener: (ev: MessageEvent) => void): void;
  close(): void;
}

export type EventSourceFactory = (url: string) => EventSourceLike;

export const EVENT_SOURCE_FACTORY = new InjectionToken<EventSourceFactory>('EVENT_SOURCE_FACTORY', {
  providedIn: 'root',
  factory: () => (url: string) => new EventSource(url) as unknown as EventSourceLike,
});

export type ConnectionState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed' | 'failed';

export type SseMessage =
  | { kind: 'event'; event: ExperimentEvent }
  | { kind: 'heartbeat'; timestamp: string | null }
  | { kind: 'end'; status: ExperimentStatus | null }
  | { kind: 'connection'; state: ConnectionState; attempt: number };

export interface SseOptions {
  /** Resume after this sequence (sent as ?lastEventId=). */
  lastEventId?: number | null;
  /** Base reconnection delay (exponential backoff). */
  reconnectDelayMs?: number;
  maxReconnectDelayMs?: number;
  /** Consecutive failed attempts before erroring. */
  maxAttempts?: number;
  /** Reconnect when no frame (event or heartbeat) arrives within this window. */
  heartbeatTimeoutMs?: number;
}

export const DEFAULT_HEARTBEAT_TIMEOUT_MS = 35_000;

export function withLastEventId(url: string, lastEventId: number | null): string {
  if (lastEventId === null || lastEventId === undefined) return url;
  const sep = url.includes('?') ? '&' : '?';
  return `${url}${sep}lastEventId=${lastEventId}`;
}

/**
 * Wraps EventSource in a cold Observable.
 * - named events: experiment-event, heartbeat, end
 * - tracks the last sequence; on error closes the source and reconnects with backoff passing ?lastEventId=
 * - heartbeat watchdog: reconnects when no frame is seen within heartbeatTimeoutMs
 * - completes on `end`; unsubscribing closes the EventSource.
 */
@Injectable({ providedIn: 'root' })
export class SseService {
  private readonly factory = inject(EVENT_SOURCE_FACTORY);

  stream(url: string, options: SseOptions = {}): Observable<SseMessage> {
    const baseDelay = options.reconnectDelayMs ?? 1000;
    const maxDelay = options.maxReconnectDelayMs ?? 15_000;
    const maxAttempts = options.maxAttempts ?? 8;
    const heartbeatTimeout = options.heartbeatTimeoutMs ?? DEFAULT_HEARTBEAT_TIMEOUT_MS;

    return new Observable<SseMessage>((subscriber) => {
      let source: EventSourceLike | null = null;
      let lastSeq: number | null = options.lastEventId ?? null;
      let attempt = 0;
      let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
      let watchdog: ReturnType<typeof setTimeout> | null = null;
      let finished = false;

      const clearTimers = () => {
        if (reconnectTimer) clearTimeout(reconnectTimer);
        if (watchdog) clearTimeout(watchdog);
        reconnectTimer = null;
        watchdog = null;
      };
      const closeSource = () => {
        if (source) {
          source.onopen = null;
          source.onerror = null;
          source.close();
          source = null;
        }
      };
      const armWatchdog = () => {
        if (watchdog) clearTimeout(watchdog);
        watchdog = setTimeout(() => scheduleReconnect(), heartbeatTimeout);
      };

      const scheduleReconnect = () => {
        if (finished) return;
        closeSource();
        clearTimers();
        attempt++;
        if (attempt > maxAttempts) {
          finished = true;
          subscriber.next({ kind: 'connection', state: 'failed', attempt });
          subscriber.error(new Error(`Event stream unavailable after ${maxAttempts} attempts`));
          return;
        }
        subscriber.next({ kind: 'connection', state: 'reconnecting', attempt });
        const delay = Math.min(maxDelay, baseDelay * 2 ** (attempt - 1));
        reconnectTimer = setTimeout(open, delay);
      };

      const open = () => {
        if (finished) return;
        if (attempt === 0) subscriber.next({ kind: 'connection', state: 'connecting', attempt });
        const es = this.factory(withLastEventId(url, lastSeq));
        source = es;
        armWatchdog();
        es.onopen = () => {
          attempt = 0;
          subscriber.next({ kind: 'connection', state: 'open', attempt });
          armWatchdog();
        };
        es.onerror = () => scheduleReconnect();
        es.addEventListener('experiment-event', (msg) => {
          armWatchdog();
          const event = parse<ExperimentEvent>(msg.data);
          if (!event || typeof event.sequence !== 'number') return;
          if (lastSeq !== null && event.sequence <= lastSeq) return; // duplicate after replay
          lastSeq = event.sequence;
          subscriber.next({ kind: 'event', event });
        });
        es.addEventListener('heartbeat', (msg) => {
          armWatchdog();
          const hb = parse<{ timestamp?: string }>(msg.data);
          subscriber.next({ kind: 'heartbeat', timestamp: hb?.timestamp ?? null });
        });
        es.addEventListener('end', (msg) => {
          const end = parse<{ status?: ExperimentStatus }>(msg.data);
          finished = true;
          clearTimers();
          closeSource();
          subscriber.next({ kind: 'end', status: end?.status ?? null });
          subscriber.next({ kind: 'connection', state: 'closed', attempt: 0 });
          subscriber.complete();
        });
      };

      open();

      return () => {
        finished = true;
        clearTimers();
        closeSource();
      };
    });
  }
}

function parse<T>(data: unknown): T | null {
  if (typeof data !== 'string' || !data.length) return null;
  try {
    return JSON.parse(data) as T;
  } catch {
    return null;
  }
}
