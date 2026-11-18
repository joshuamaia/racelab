import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription, catchError, of, switchMap, takeWhile, timer } from 'rxjs';
import { ButtonModule } from 'primeng/button';
import { ChartModule } from 'primeng/chart';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { MultiSelectModule } from 'primeng/multiselect';
import { ProgressBarModule } from 'primeng/progressbar';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TooltipModule } from 'primeng/tooltip';
import { RacelabApi } from '../../core/api/racelab-api.service';
import {
  ConfigurationField,
  FIELD_BY_KEY,
  diffConfiguration,
  validateConfiguration,
} from '../../core/config/configuration-fields';
import {
  Comparison,
  ExperimentConfiguration,
  ParameterKey,
  ScenarioDescriptor,
  StrategyType,
} from '../../core/models/api.models';
import { CatalogService } from '../../core/state/catalog.service';
import { PreferencesService } from '../../core/state/preferences.service';
import { baseChartOptions, chartPalette } from '../../core/util/chart-theme';
import { formatDateTime, formatMs, formatNumber } from '../../core/util/format';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { StatusTagComponent } from '../../shared/status/status-tag.component';
import { toComparisonRows } from './comparison-table';

export const MIN_STRATEGIES = 2;
export const MAX_STRATEGIES = 8;

@Component({
  selector: 'rl-comparator',
  imports: [
    FormsModule,
    RouterLink,
    ButtonModule,
    ChartModule,
    InputNumberModule,
    InputTextModule,
    MultiSelectModule,
    ProgressBarModule,
    SelectModule,
    TableModule,
    ToggleSwitchModule,
    TooltipModule,
    EmptyStateComponent,
    StatusTagComponent,
  ],
  templateUrl: './comparator.component.html',
  styleUrl: './comparator.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComparatorComponent implements OnInit {
  private readonly api = inject(RacelabApi);
  private readonly route = inject(ActivatedRoute);
  private readonly prefs = inject(PreferencesService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly catalog = inject(CatalogService);

  protected readonly scenarioId = signal<string | null>(null);
  protected readonly strategies = signal<StrategyType[]>([]);
  protected readonly config = signal<ExperimentConfiguration | null>(null);
  protected readonly repetitions = signal(3);
  protected readonly warmupRuns = signal(1);
  protected readonly name = signal('');
  protected readonly submitting = signal(false);
  protected readonly comparison = signal<Comparison | null>(null);
  protected readonly previous = signal<Comparison[]>([]);
  protected readonly previousLoading = signal(false);
  private poll: Subscription | null = null;
  private pendingConfig: Partial<ExperimentConfiguration> | null = null;

  protected readonly scenarioOptions = computed(() =>
    this.catalog.scenarios().map((s) => ({ label: `${s.name} (${s.category.toLowerCase()})`, value: s.id })),
  );
  protected readonly scenario = computed<ScenarioDescriptor | undefined>(() => this.catalog.scenario(this.scenarioId()));
  protected readonly strategyOptions = computed(() =>
    (this.scenario()?.supportedStrategies ?? []).map((s) => ({ label: this.catalog.strategyName(s), value: s })),
  );
  protected readonly fields = computed<ConfigurationField[]>(() => {
    const s = this.scenario();
    if (!s) return [];
    return s.relevantParameters
      .map((k) => FIELD_BY_KEY.get(k))
      .filter((f): f is ConfigurationField => !!f && f.key !== 'executionMode');
  });
  protected readonly errors = computed(() => validateConfiguration(this.config() ?? {}));
  protected readonly strategyCountError = computed(() => {
    const n = this.strategies().length;
    if (n < MIN_STRATEGIES) return `Select at least ${MIN_STRATEGIES} strategies`;
    if (n > MAX_STRATEGIES) return `Select at most ${MAX_STRATEGIES} strategies`;
    return null;
  });
  protected readonly canSubmit = computed(
    () =>
      !!this.scenario() &&
      !this.strategyCountError() &&
      Object.keys(this.errors()).length === 0 &&
      this.repetitions() >= 1 &&
      this.repetitions() <= 10 &&
      this.warmupRuns() >= 0 &&
      this.warmupRuns() <= 3 &&
      !this.submitting() &&
      !this.isRunning(),
  );
  protected readonly isRunning = computed(() => {
    const s = this.comparison()?.status;
    return s === 'RUNNING' || s === 'CREATED';
  });
  protected readonly progress = computed(() => {
    const p = this.comparison()?.progress;
    if (!p || !p.totalRuns) return 0;
    return Math.round((p.completedRuns / p.totalRuns) * 100);
  });
  protected readonly rows = computed(() => {
    const c = this.comparison();
    return c ? toComparisonRows(c, (s) => this.catalog.strategyName(s)) : [];
  });

  protected readonly charts = computed(() => {
    this.prefs.theme();
    const rows = this.rows();
    if (!rows.length) return null;
    const p = chartPalette();
    const labels = rows.map((r) => r.strategyName);
    const bar = (label: string, data: number[], color: string) => ({ label, data, backgroundColor: color, borderRadius: 3 });
    return {
      latency: {
        data: {
          labels,
          datasets: [
            bar('Avg latency (ms)', rows.map((r) => r.avgLatencyMs), p.blue),
            bar('p95 latency (ms)', rows.map((r) => r.p95LatencyMs), p.cyan),
            bar('Lock wait (ms)', rows.map((r) => r.lockWaitTimeMs), p.yellow),
          ],
        },
        options: baseChartOptions(p),
      },
      outcomes: {
        data: {
          labels,
          datasets: [
            bar('Committed', rows.map((r) => r.committed), p.green),
            bar('Rolled back', rows.map((r) => r.rolledBack), p.red),
            bar('Business rejections', rows.map((r) => r.businessRejections), p.yellow),
            bar('Conflicts', rows.map((r) => r.conflicts), p.orange),
            bar('Retries', rows.map((r) => r.retries), p.purple),
            bar('Inconsistencies', rows.map((r) => r.inconsistencies), p.series[7]),
          ],
        },
        options: baseChartOptions(p),
      },
      throughput: {
        data: { labels, datasets: [bar('Throughput (tx/s)', rows.map((r) => r.throughputTps), p.cyan)] },
        options: baseChartOptions(p, { legend: false }),
      },
    };
  });

  protected readonly formatMs = formatMs;
  protected readonly formatNumber = formatNumber;
  protected readonly formatDateTime = formatDateTime;

  ngOnInit(): void {
    const qp = this.route.snapshot.queryParamMap;
    const qpScenario = qp.get('scenarioId');
    const qpStrategies = (qp.get('strategies') ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean) as StrategyType[];
    const qpConfig = qp.get('config');
    if (qpConfig) {
      try {
        this.pendingConfig = JSON.parse(qpConfig) as Partial<ExperimentConfiguration>;
      } catch {
        this.pendingConfig = null;
      }
    }
    const qpComparison = qp.get('comparisonId');

    this.catalog
      .ensureLoaded()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        const first = qpScenario && this.catalog.scenario(qpScenario) ? qpScenario : this.catalog.scenarios()[0]?.id ?? null;
        if (first) this.selectScenario(first, qpStrategies.length ? qpStrategies : null);
      });
    this.loadPrevious();
    if (qpComparison) this.open(qpComparison);
  }

  protected selectScenario(id: string, strategies: StrategyType[] | null = null): void {
    this.scenarioId.set(id);
    const s = this.catalog.scenario(id);
    if (!s) return;
    const supported = s.supportedStrategies;
    const chosen = (strategies ?? []).filter((x) => supported.includes(x));
    this.strategies.set(
      (chosen.length >= MIN_STRATEGIES ? chosen : this.defaultStrategies(s, chosen)).slice(0, MAX_STRATEGIES),
    );
    this.config.set({ ...s.defaults, ...(this.pendingConfig ?? {}), executionMode: 'CONCURRENT' });
    this.pendingConfig = null;
  }

  private defaultStrategies(s: ScenarioDescriptor, seed: StrategyType[]): StrategyType[] {
    const set = new Set<StrategyType>(seed);
    set.add(s.defaultStrategy);
    for (const x of s.supportedStrategies) {
      if (set.size >= Math.min(4, s.supportedStrategies.length)) break;
      set.add(x);
    }
    return [...set];
  }

  protected setField(key: ParameterKey, value: unknown): void {
    this.config.update((c) => (c ? ({ ...c, [key]: value } as ExperimentConfiguration) : c));
  }

  protected fieldValue(key: ParameterKey): unknown {
    const c = this.config();
    return c ? c[key] : null;
  }

  protected fieldLabel(f: ConfigurationField): string {
    return this.scenario()?.parameterLabels[f.key] ?? f.label;
  }

  protected errorOf(key: ParameterKey): string | undefined {
    return this.errors()[key];
  }

  protected resetConfig(): void {
    const s = this.scenario();
    if (s) this.config.set({ ...s.defaults, executionMode: 'CONCURRENT' });
  }

  protected submit(): void {
    const s = this.scenario();
    const config = this.config();
    if (!s || !config || !this.canSubmit()) return;
    this.submitting.set(true);
    this.api
      .createComparison({
        name: this.name().trim() || null,
        scenarioId: s.id,
        strategies: this.strategies(),
        configuration: diffConfiguration(config, s.defaults),
        repetitions: this.repetitions(),
        warmupRuns: this.warmupRuns(),
      })
      .subscribe({
        next: (c) => {
          this.submitting.set(false);
          this.comparison.set(c);
          this.startPolling(c.id);
          this.loadPrevious();
        },
        error: () => this.submitting.set(false),
      });
  }

  protected cancel(): void {
    const c = this.comparison();
    if (!c) return;
    this.api.cancelComparison(c.id).subscribe((updated) => {
      if (updated && typeof updated === 'object' && 'id' in updated) this.comparison.set(updated);
    });
  }

  protected open(id: string): void {
    this.api.comparison(id).subscribe((c) => {
      this.comparison.set(c);
      if (c.status === 'RUNNING' || c.status === 'CREATED') this.startPolling(c.id);
      else this.stopPolling();
    });
  }

  protected loadPrevious(): void {
    this.previousLoading.set(true);
    this.api
      .comparisons({ silent: true })
      .pipe(catchError(() => of([] as Comparison[])))
      .subscribe((list) => {
        this.previous.set(list);
        this.previousLoading.set(false);
      });
  }

  private startPolling(id: string): void {
    this.stopPolling();
    this.poll = timer(1000, 1000)
      .pipe(
        switchMap(() => this.api.comparison(id, { silent: true }).pipe(catchError(() => of(null)))),
        takeWhile((c) => !c || c.status === 'RUNNING' || c.status === 'CREATED', true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((c) => {
        if (!c) return;
        this.comparison.set(c);
        if (c.status !== 'RUNNING' && c.status !== 'CREATED') this.loadPrevious();
      });
  }

  private stopPolling(): void {
    this.poll?.unsubscribe();
    this.poll = null;
  }
}
