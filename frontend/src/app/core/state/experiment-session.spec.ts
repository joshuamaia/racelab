import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';
import { details, ev } from '../../../testing/fixtures';
import { MockEventSource } from '../../../testing/mock-event-source';
import { errorInterceptor, instanceInterceptor } from '../api/interceptors';
import { EVENT_SOURCE_FACTORY } from '../sse/sse.service';
import { ExperimentSession } from './experiment-session.service';
import { ExperimentStore } from './experiment-store';

describe('ExperimentStore', () => {
  it('deduplicates by sequence, keeps order and derives status from terminal events', () => {
    const store = new ExperimentStore();
    store.reset(details({ status: 'RUNNING' }));
    expect(store.addEvents([ev(2, 'READ_COMPLETED', 'worker-1', 5), ev(1, 'EXPERIMENT_STARTED', null, 0)])).toBe(2);
    expect(store.addEvents([ev(2, 'READ_COMPLETED', 'worker-1', 5)])).toBe(0);
    store.addEvents([ev(3, 'EXPERIMENT_COMPLETED', null, 9)]);
    expect(store.events().map((e) => e.sequence)).toEqual([1, 2, 3]);
    expect(store.lastSequence()).toBe(3);
    expect(store.status()).toBe('COMPLETED');
    expect(store.lanes().map((l) => l.id)).toEqual(['__experiment__', 'worker-1']);
  });

  it('never moves a terminal status back to RUNNING because of a stale response', () => {
    const store = new ExperimentStore();
    store.reset(details({ status: 'COMPLETED' }));
    store.setDetails(details({ status: 'RUNNING' }));
    expect(store.status()).toBe('COMPLETED');
  });
});

describe('ExperimentSession', () => {
  let http: HttpTestingController;
  let session: ExperimentSession;

  beforeEach(() => {
    MockEventSource.reset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([instanceInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
        MessageService,
        ExperimentSession,
        { provide: EVENT_SOURCE_FACTORY, useValue: (url: string) => new MockEventSource(url) },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(ExperimentSession);
  });


  it('runs create(autoStart:false) → SSE → start', () => {
    session.run({ scenarioId: 'bank-withdrawal', strategy: 'NONE', autoStart: true }).subscribe();
    const create = http.expectOne('/api/experiments');
    expect(create.request.method).toBe('POST');
    expect(create.request.body.autoStart).toBe(false);
    create.flush(details());

    expect(MockEventSource.last().url).toBe('/api/experiments/EXP-1/events?instance=primary');
    const start = http.expectOne('/api/experiments/EXP-1/start');
    expect(start.request.headers.get('X-RaceLab-Instance')).toBe('primary');
    start.flush(details({ status: 'RUNNING' }));
    expect(session.store.status()).toBe('RUNNING');

    MockEventSource.last().emit('experiment-event', ev(1, 'EXPERIMENT_STARTED', null, 0));
    expect(session.store.events().length).toBe(1);
  });

  it('cancel calls the cancel endpoint and closes the stream', () => {
    session.run({ scenarioId: 'bank-withdrawal', strategy: 'NONE' }).subscribe();
    http.expectOne('/api/experiments').flush(details());
    http.expectOne('/api/experiments/EXP-1/start').flush(details({ status: 'RUNNING' }));
    const es = MockEventSource.last();
    expect(es.closed).toBe(false);

    session.cancel().subscribe();
    const cancel = http.expectOne('/api/experiments/EXP-1/cancel');
    expect(cancel.request.method).toBe('POST');
    cancel.flush(details({ status: 'CANCELLED' }));

    expect(es.closed).toBe(true);
    expect(session.store.status()).toBe('CANCELLED');
    expect(session.store.connection()).toBe('closed');
    // events emitted after cancel are not consumed anymore
    es.emit('experiment-event', ev(5, 'READ_COMPLETED', 'worker-1', 1));
    expect(session.store.events().length).toBe(0);
  });

  it('loads the result when the stream ends', async () => {
    session.run({ scenarioId: 'bank-withdrawal', strategy: 'NONE' }).subscribe();
    http.expectOne('/api/experiments').flush(details());
    http.expectOne('/api/experiments/EXP-1/start').flush(details({ status: 'RUNNING' }));
    const es = MockEventSource.last();
    es.emit('experiment-event', ev(1, 'EXPERIMENT_COMPLETED', null, 10));
    es.emit('end', { status: 'COMPLETED' });
    expect(es.closed).toBe(true);
    http.expectOne('/api/experiments/EXP-1').flush(details({ status: 'COMPLETED', consistent: false }));
    http.expectOne('/api/experiments/EXP-1/result').flush({ experimentId: 'EXP-1', consistent: false, stateRows: [] });
    expect(session.store.result()?.consistent).toBe(false);
  });
});
