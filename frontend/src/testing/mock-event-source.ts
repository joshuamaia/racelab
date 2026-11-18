import { EventSourceLike } from '../app/core/sse/sse.service';

/** In-memory EventSource double used by SSE tests. */
export class MockEventSource implements EventSourceLike {
  static instances: MockEventSource[] = [];
  readyState = 0;
  closed = false;
  onopen: ((ev: Event) => unknown) | null = null;
  onerror: ((ev: Event) => unknown) | null = null;
  private listeners = new Map<string, ((ev: MessageEvent) => void)[]>();

  constructor(public readonly url: string) {
    MockEventSource.instances.push(this);
  }

  static reset(): void {
    MockEventSource.instances = [];
  }

  static last(): MockEventSource {
    return MockEventSource.instances[MockEventSource.instances.length - 1];
  }

  addEventListener(type: string, listener: (ev: MessageEvent) => void): void {
    const list = this.listeners.get(type) ?? [];
    list.push(listener);
    this.listeners.set(type, list);
  }

  close(): void {
    this.closed = true;
    this.readyState = 2;
  }

  open(): void {
    this.readyState = 1;
    this.onopen?.(new Event('open'));
  }

  emit(type: string, data: unknown, id?: string): void {
    const ev = new MessageEvent(type, { data: typeof data === 'string' ? data : JSON.stringify(data), lastEventId: id ?? '' });
    for (const l of this.listeners.get(type) ?? []) l(ev);
  }

  fail(): void {
    this.onerror?.(new Event('error'));
  }
}
