import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EMPTY, Subject, catchError, combineLatest, map, merge, of, switchMap, timer } from 'rxjs';
import { ButtonModule } from 'primeng/button';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TableModule } from 'primeng/table';
import { TooltipModule } from 'primeng/tooltip';
import { RacelabApi } from '../../core/api/racelab-api.service';
import { apiErrorOf } from '../../core/api/interceptors';
import {
  ExperimentConfiguration,
  ExperimentDetails,
  ExperimentPreset,
  InspectorScope,
  InspectorSession,
  InspectorSnapshot,
} from '../../core/models/api.models';
import { CatalogService } from '../../core/state/catalog.service';
import { formatDateTime, formatMs } from '../../core/util/format';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { LockGraphComponent } from '../../shared/lock-graph/lock-graph.component';

export interface BlockingEdge {
  blockerPid: number;
  blockerLabel: string;
  blockedPid: number;
  blockedLabel: string;
}

/** Derives "A —blocks→ B" pairs from pg_blocking_pids (session.blockedBy). */
export function blockingEdges(sessions: readonly InspectorSession[]): BlockingEdge[] {
  const byPid = new Map(sessions.map((s) => [s.pid, s]));
  const label = (pid: number) => {
    const s = byPid.get(pid);
    const who = s?.workerId ?? s?.applicationName ?? null;
    return who ? `pid ${pid} (${who})` : `pid ${pid}`;
  };
  const out: BlockingEdge[] = [];
  for (const s of sessions) {
    for (const blocker of s.blockedBy ?? []) {
      out.push({ blockerPid: blocker, blockerLabel: label(blocker), blockedPid: s.pid, blockedLabel: label(s.pid) });
    }
  }
  return out;
}

/** Longer delays so the user can watch live blocking in the inspector. */
export const LIVE_DEMO_OVERRIDES: Partial<ExperimentConfiguration> = {
  delayBeforeCommitMs: 3000,
  delayAfterReadMs: 1500,
  lockTimeoutMs: 15000,
  transactionTimeoutMs: 30000,
  executionMode: 'CONCURRENT',
};

type DemoKind = 'deadlock' | 'pessimistic';

@Component({
  selector: 'rl-inspector',
  imports: [FormsModule, RouterLink, ButtonModule, SelectButtonModule, TableModule, TooltipModule, EmptyStateComponent, LockGraphComponent],
  templateUrl: './inspector.component.html',
  styleUrl: './inspector.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InspectorComponent implements OnInit {
  private readonly api = inject(RacelabApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly catalog = inject(CatalogService);

  protected readonly refreshOptions = [
    { label: '1s', value: 1000 },
    { label: '2s', value: 2000 },
    { label: '5s', value: 5000 },
    { label: 'Off', value: 0 },
  ];
  protected readonly scopeOptions: { label: string; value: InspectorScope }[] = [
    { label: 'RaceLab sessions', value: 'racelab' },
    { label: 'Whole database', value: 'database' },
  ];

  protected readonly refreshMs = signal(2000);
  protected readonly scope = signal<InspectorScope>('racelab');
  protected readonly snapshot = signal<InspectorSnapshot | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly launching = signal<DemoKind | null>(null);
  protected readonly launched = signal<ExperimentDetails | null>(null);
  private readonly manual$ = new Subject<void>();

  protected readonly edges = computed(() => blockingEdges(this.snapshot()?.sessions ?? []));
  protected readonly waitingCount = computed(() => (this.snapshot()?.sessions ?? []).filter((s) => s.blockedBy?.length).length);
  protected readonly notGranted = computed(() => (this.snapshot()?.locks ?? []).filter((l) => !l.granted).length);
  protected readonly deadlockPreset = computed(() => this.findPreset('deadlock'));
  protected readonly pessimisticPreset = computed(() => this.findPreset('pessimistic'));

  protected readonly formatMs = formatMs;
  protected readonly formatDateTime = formatDateTime;

  constructor() {
    const auto$ = combineLatest([toObservable(this.refreshMs), toObservable(this.scope)]).pipe(
      switchMap(([ms]) => (ms > 0 ? timer(0, ms) : of(0))),
    );
    merge(auto$, this.manual$)
      .pipe(
        switchMap(() => {
          this.loading.set(true);
          return this.api.inspectorSnapshot(this.scope(), { silent: true }).pipe(
            map((s) => ({ ok: true as const, s })),
            catchError((err) => of({ ok: false as const, err })),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((r) => {
        this.loading.set(false);
        if (r.ok) {
          this.snapshot.set(r.s);
          this.error.set(null);
        } else {
          this.error.set(apiErrorOf(r.err)?.message ?? 'Snapshot unavailable');
        }
      });
  }

  ngOnInit(): void {
    this.catalog.ensureLoaded().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }

  protected refreshNow(): void {
    this.manual$.next();
  }

  protected launch(kind: DemoKind): void {
    const preset = kind === 'deadlock' ? this.deadlockPreset() : this.pessimisticPreset();
    if (!preset) return;
    this.launching.set(kind);
    this.api
      .createExperiment({
        presetId: preset.id,
        scenarioId: preset.scenarioId,
        strategy: kind === 'pessimistic' ? 'PESSIMISTIC_LOCKING' : preset.strategy,
        name: `${preset.name} (inspector live demo)`,
        configuration: { ...LIVE_DEMO_OVERRIDES },
        autoStart: true,
      })
      .pipe(catchError(() => EMPTY))
      .subscribe({
        next: (d) => {
          this.launched.set(d);
          this.refreshMs.set(1000);
          this.launching.set(null);
        },
        complete: () => this.launching.set(null),
      });
  }

  private findPreset(kind: DemoKind): ExperimentPreset | undefined {
    const presets = this.catalog.presets();
    if (kind === 'deadlock') {
      const has = (p: ExperimentPreset) =>
        [p.id, p.scenarioId, ...(p.tags ?? [])].some((x) => x.toLowerCase().includes('deadlock'));
      return presets.find((p) => has(p) && p.demo !== null) ?? presets.find(has);
    }
    const candidates = presets.filter((p) => p.category === 'LOCKING' || p.category === 'CONCURRENCY');
    return (
      candidates.find((p) => p.strategy === 'PESSIMISTIC_LOCKING') ??
      candidates.find((p) => p.availableStrategies.includes('PESSIMISTIC_LOCKING'))
    );
  }
}
