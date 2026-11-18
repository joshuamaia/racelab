import { TestBed } from '@angular/core/testing';
import { ev } from '../../../testing/fixtures';
import { MockEventSource } from '../../../testing/mock-event-source';
import { EVENT_SOURCE_FACTORY, SseMessage, SseService, withLastEventId } from './sse.service';

describe('SseService', () => {
  let service: SseService;

  beforeEach(() => {
    vi.useFakeTimers();
    MockEventSource.reset();
    TestBed.configureTestingModule({
      providers: [{ provide: EVENT_SOURCE_FACTORY, useValue: (url: string) => new MockEventSource(url) }],
    });
    service = TestBed.inject(SseService);
  });

  afterEach(() => vi.useRealTimers());

  const collect = (url = '/api/experiments/EXP-1/events', opts = {}) => {
    const messages: SseMessage[] = [];
    let completed = false;
    let error: unknown = null;
    const sub = service.stream(url, { reconnectDelayMs: 100, ...opts }).subscribe({
      next: (m) => messages.push(m),
      complete: () => (completed = true),
      error: (e) => (error = e),
    });
    return { messages, sub, isCompleted: () => completed, error: () => error };
  };
  const events = (msgs: SseMessage[]) => msgs.filter((m) => m.kind === 'event').map((m) => (m.kind === 'event' ? m.event.sequence : -1));

  it('emits named experiment events, heartbeats and completes on end', () => {
    const { messages, isCompleted } = collect();
    const es = MockEventSource.last();
    es.open();
    es.emit('experiment-event', ev(1, 'EXPERIMENT_STARTED', null, 0), '1');
    es.emit('heartbeat', { timestamp: 'now' });
    es.emit('experiment-event', ev(2, 'READ_COMPLETED', 'worker-1', 5), '2');
    es.emit('end', { status: 'COMPLETED' });

    expect(events(messages)).toEqual([1, 2]);
    expect(messages.some((m) => m.kind === 'heartbeat')).toBe(true);
    expect(messages.find((m) => m.kind === 'end')).toEqual({ kind: 'end', status: 'COMPLETED' });
    expect(isCompleted()).toBe(true);
    expect(es.closed).toBe(true);
  });

  it('drops duplicate / older sequences', () => {
    const { messages } = collect();
    const es = MockEventSource.last();
    es.emit('experiment-event', ev(1, 'EXPERIMENT_STARTED', null, 0));
    es.emit('experiment-event', ev(2, 'READ_COMPLETED', 'worker-1', 5));
    es.emit('experiment-event', ev(2, 'READ_COMPLETED', 'worker-1', 5));
    es.emit('experiment-event', ev(1, 'EXPERIMENT_STARTED', null, 0));
    es.emit('experiment-event', 'not json');
    expect(events(messages)).toEqual([1, 2]);
  });

  it('reconnects with backoff passing ?lastEventId=<last sequence>', () => {
    const { messages } = collect();
    const first = MockEventSource.last();
    first.open();
    first.emit('experiment-event', ev(1, 'EXPERIMENT_STARTED', null, 0));
    first.emit('experiment-event', ev(7, 'READ_COMPLETED', 'worker-1', 5));
    first.fail();

    expect(first.closed).toBe(true);
    expect(messages.some((m) => m.kind === 'connection' && m.state === 'reconnecting')).toBe(true);
    expect(MockEventSource.instances.length).toBe(1);
    vi.advanceTimersByTime(100);
    expect(MockEventSource.instances.length).toBe(2);
    const second = MockEventSource.last();
    expect(second.url).toBe('/api/experiments/EXP-1/events?lastEventId=7');

    // replayed duplicates are ignored, new ones flow
    second.open();
    second.emit('experiment-event', ev(7, 'READ_COMPLETED', 'worker-1', 5));
    second.emit('experiment-event', ev(8, 'WRITE_COMPLETED', 'worker-1', 9));
    expect(events(messages)).toEqual([1, 7, 8]);
  });

  it('uses exponential backoff for consecutive failures', () => {
    collect();
    MockEventSource.last().fail();
    vi.advanceTimersByTime(100);
    expect(MockEventSource.instances.length).toBe(2);
    MockEventSource.last().fail();
    vi.advanceTimersByTime(150);
    expect(MockEventSource.instances.length).toBe(2);
    vi.advanceTimersByTime(50);
    expect(MockEventSource.instances.length).toBe(3);
  });

  it('starts from options.lastEventId when resuming', () => {
    collect('/api/experiments/EXP-1/events?instance=secondary', { lastEventId: 12 });
    expect(MockEventSource.last().url).toBe('/api/experiments/EXP-1/events?instance=secondary&lastEventId=12');
  });

  it('reconnects when the heartbeat watchdog expires', () => {
    collect('/x', { heartbeatTimeoutMs: 1000 });
    const first = MockEventSource.last();
    first.open();
    vi.advanceTimersByTime(900);
    first.emit('heartbeat', { timestamp: 't' });
    vi.advanceTimersByTime(900);
    expect(first.closed).toBe(false);
    vi.advanceTimersByTime(200);
    expect(first.closed).toBe(true);
    vi.advanceTimersByTime(100);
    expect(MockEventSource.instances.length).toBe(2);
  });

  it('errors after the maximum number of attempts', () => {
    const { error } = collect('/x', { maxAttempts: 2 });
    MockEventSource.last().fail();
    vi.advanceTimersByTime(100);
    MockEventSource.last().fail();
    vi.advanceTimersByTime(200);
    MockEventSource.last().fail();
    expect(error()).toBeInstanceOf(Error);
  });

  it('closes the EventSource on unsubscribe and does not reconnect', () => {
    const { sub } = collect();
    const es = MockEventSource.last();
    sub.unsubscribe();
    expect(es.closed).toBe(true);
    vi.advanceTimersByTime(60_000);
    expect(MockEventSource.instances.length).toBe(1);
  });

  it('builds lastEventId urls', () => {
    expect(withLastEventId('/a', null)).toBe('/a');
    expect(withLastEventId('/a', 0)).toBe('/a?lastEventId=0');
    expect(withLastEventId('/a?b=1', 3)).toBe('/a?b=1&lastEventId=3');
  });
});
