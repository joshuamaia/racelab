import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import {
  Observable,
  Subject,
  Subscription,
  auditTime,
  catchError,
  defer,
  delay,
  EMPTY,
  finalize,
  forkJoin,
  of,
  retry,
  switchMap,
  tap,
  throwError,
  timer,
} from 'rxjs';
import { RacelabApi } from '../api/racelab-api.service';
import { apiErrorOf } from '../api/interceptors';
import { CreateExperimentRequest, ExperimentDetails, ExportFormat, ExperimentResult } from '../models/api.models';
import { SseService } from '../sse/sse.service';
import { downloadBlob, filenameFromDisposition } from '../util/download';
import { ExperimentStore } from './experiment-store';
import { PreferencesService } from './preferences.service';

export const RESULT_RETRY_DELAY_MS = 600;
export const RESULT_MAX_RETRIES = 10;

/**
 * Orchestrates one experiment at a time for a page (provide it at component level):
 * create (autoStart:false) → open SSE → start; step / resume / cancel / rerun; result loading; export.
 */
@Injectable()
export class ExperimentSession {
  private readonly api = inject(RacelabApi);
  private readonly sse = inject(SseService);
  private readonly prefs = inject(PreferencesService);

  readonly store = new ExperimentStore();
  readonly busy = signal<null | 'create' | 'start' | 'step' | 'resume' | 'cancel' | 'rerun' | 'load' | 'export'>(null);
  readonly error = signal<string | null>(null);

  /** Backend instance that owns the current experiment (X-RaceLab-Instance). */
  private instance: string | null = null;
  private stream: Subscription | null = null;
  private readonly refresh$ = new Subject<void>();
  private readonly refreshSub: Subscription;

  constructor() {
    this.refreshSub = this.refresh$
      .pipe(
        auditTime(150),
        switchMap(() => {
          const id = this.store.id();
          return id ? this.api.experiment(id, this.opts(true)).pipe(catchError(() => EMPTY)) : EMPTY;
        }),
      )
      .subscribe((d) => this.store.setDetails(d));
    inject(DestroyRef).onDestroy(() => this.dispose());
  }

  get targetInstance(): string | null {
    return this.instance;
  }

  /** Create → SSE → start. */
  run(request: CreateExperimentRequest, instance: string | null = null): Observable<ExperimentDetails> {
    this.closeStream();
    this.store.reset(null);
    this.error.set(null);
    this.instance = instance;
    this.busy.set('create');
    return this.api.createExperiment({ ...request, autoStart: false }, this.opts()).pipe(
      switchMap((created) => this.startCreated(created)),
      finalize(() => this.busy.set(null)),
      catchError((err) => this.fail(err)),
    );
  }

  /** Rerun the current experiment (new id, isolated data) with the same flow. */
  rerun(): Observable<ExperimentDetails> {
    const id = this.store.id();
    if (!id) return EMPTY;
    this.closeStream();
    this.busy.set('rerun');
    return this.api.rerun(id, false, this.opts()).pipe(
      tap(() => this.store.reset(null)),
      switchMap((created) => this.startCreated(created)),
      finalize(() => this.busy.set(null)),
      catchError((err) => this.fail(err)),
    );
  }

  /** Attach to an existing experiment (detail page): persisted events, then live SSE when still running. */
  attach(id: string): Observable<ExperimentDetails> {
    this.closeStream();
    this.store.reset(null);
    this.error.set(null);
    this.busy.set('load');
    return this.api.experiment(id).pipe(
      tap((details) => {
        this.instance = details.instanceId;
        this.store.setDetails(details);
      }),
      switchMap((details) =>
        this.api.events(id, this.opts(true)).pipe(
          catchError(() => of([])),
          tap((events) => {
            this.store.addEvents(events);
            if (this.store.isTerminal()) this.loadResult().subscribe();
            else if (details.status === 'RUNNING' || details.status === 'CREATED') this.connect();
          }),
          switchMap(() => of(details)),
        ),
      ),
      finalize(() => this.busy.set(null)),
      catchError((err) => this.fail(err)),
    );
  }

  start(): Observable<ExperimentDetails> {
    const id = this.store.id();
    if (!id) return EMPTY;
    this.busy.set('start');
    if (!this.stream) this.connect();
    return this.api.start(id, this.opts()).pipe(
      tap((d) => this.store.setDetails(d)),
      finalize(() => this.busy.set(null)),
    );
  }

  step(workerId?: string | null): Observable<ExperimentDetails> {
    const id = this.store.id();
    if (!id) return EMPTY;
    this.busy.set('step');
    return this.api.step(id, workerId ?? null, this.opts()).pipe(
      tap((d) => this.store.setDetails(d)),
      finalize(() => this.busy.set(null)),
    );
  }

  resume(): Observable<ExperimentDetails> {
    const id = this.store.id();
    if (!id) return EMPTY;
    this.busy.set('resume');
    return this.api.resume(id, this.opts()).pipe(
      tap((d) => {
        if (d && typeof d === 'object' && 'id' in d) this.store.setDetails(d);
      }),
      finalize(() => this.busy.set(null)),
    );
  }

  /** Cancels cooperatively, closes the live stream and loads what was persisted. */
  cancel(): Observable<ExperimentDetails> {
    const id = this.store.id();
    if (!id) return EMPTY;
    this.busy.set('cancel');
    return this.api.cancel(id, this.opts()).pipe(
      tap((d) => {
        if (d && typeof d === 'object' && 'id' in d) this.store.setDetails(d);
        this.closeStream();
        this.store.connection.set('closed');
        this.loadPersisted(id);
      }),
      finalize(() => this.busy.set(null)),
    );
  }

  /** Re-attaches the live stream (manual retry after the stream failed). */
  reconnect(): void {
    if (this.store.id()) this.connect();
  }

  loadResult(): Observable<ExperimentResult | null> {
    const id = this.store.id();
    if (!id) return of(null);
    this.store.resultLoading.set(true);
    return defer(() => this.api.result(id, this.opts(true))).pipe(
      retry({
        count: RESULT_MAX_RETRIES,
        delay: (err) => {
          const apiErr = apiErrorOf(err);
          return apiErr?.code === 'RESULT_NOT_AVAILABLE' || apiErr?.status === 404
            ? timer(RESULT_RETRY_DELAY_MS)
            : throwError(() => err);
        },
      }),
      tap((r) => this.store.result.set(r)),
      catchError(() => of(null)),
      finalize(() => this.store.resultLoading.set(false)),
    );
  }

  exportResult(format: ExportFormat): Observable<void> {
    const id = this.store.id();
    if (!id) return EMPTY;
    this.busy.set('export');
    return this.api.exportExperiment(id, format, this.opts()).pipe(
      tap((res) => {
        const ext = format === 'markdown' ? 'md' : format;
        const name = filenameFromDisposition(res.headers.get('Content-Disposition'), `${id}.${ext}`);
        if (res.body) downloadBlob(res.body, name);
      }),
      switchMap(() => of(undefined)),
      finalize(() => this.busy.set(null)),
    );
  }

  closeStream(): void {
    this.stream?.unsubscribe();
    this.stream = null;
  }

  private startCreated(created: ExperimentDetails): Observable<ExperimentDetails> {
    this.instance = created.instanceId ?? this.instance;
    this.store.reset(created);
    this.connect();
    this.busy.set('start');
    return this.api.start(created.id, this.opts()).pipe(tap((d) => this.store.setDetails(d)));
  }

  private connect(): void {
    const id = this.store.id();
    if (!id) return;
    this.closeStream();
    const url = this.api.eventStreamUrl(id, this.instance);
    this.stream = this.sse
      .stream(url, { lastEventId: this.store.lastSequence(), reconnectDelayMs: this.prefs.sseReconnectDelayMs() })
      .subscribe({
        next: (msg) => {
          switch (msg.kind) {
            case 'event': {
              this.store.addEvents([msg.event]);
              const t = msg.event.eventType;
              if (t.startsWith('CHECKPOINT_') || (this.store.isStepMode() && t === 'LOCK_WAITING')) this.refresh$.next();
              break;
            }
            case 'heartbeat':
              this.store.lastHeartbeat.set(new Date());
              break;
            case 'connection':
              this.store.connection.set(msg.state);
              this.store.reconnectAttempt.set(msg.attempt);
              break;
            case 'end':
              if (msg.status) this.store.setStatus(msg.status);
              this.onStreamEnd(id);
              break;
          }
        },
        error: () => {
          this.store.connection.set('failed');
          this.stream = null;
          // fall back to persisted data so the page stays consistent
          this.loadPersisted(id);
        },
      });
  }

  private onStreamEnd(id: string): void {
    this.stream = null;
    this.api
      .experiment(id, this.opts(true))
      .pipe(catchError(() => EMPTY))
      .subscribe((d) => this.store.setDetails(d));
    this.loadResult().subscribe();
  }

  private loadPersisted(id: string): void {
    forkJoin({
      details: this.api.experiment(id, this.opts(true)).pipe(catchError(() => of(null))),
      events: this.api.events(id, this.opts(true)).pipe(catchError(() => of([]))),
    })
      .pipe(delay(0))
      .subscribe(({ details, events }) => {
        if (details) this.store.setDetails(details);
        this.store.addEvents(events);
        this.loadResult().subscribe();
      });
  }

  private opts(silent = false) {
    return { instance: this.instance, silent };
  }

  private fail(err: unknown): Observable<never> {
    this.error.set(apiErrorOf(err)?.message ?? 'Unexpected error');
    return throwError(() => err);
  }

  private dispose(): void {
    this.closeStream();
    this.refreshSub.unsubscribe();
  }
}
