import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ScrollingModule } from '@angular/cdk/scrolling';
import { TabsModule } from 'primeng/tabs';
import { AccordionModule } from 'primeng/accordion';
import { ChartModule } from 'primeng/chart';
import { TableModule } from 'primeng/table';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { SkeletonModule } from 'primeng/skeleton';
import { TooltipModule } from 'primeng/tooltip';
import { catchError, of } from 'rxjs';
import { RacelabApi } from '../../../core/api/racelab-api.service';
import { CodeExample, CodeSnippet, ExperimentEvent, ExperimentResult, StrategyType } from '../../../core/models/api.models';
import { CatalogService } from '../../../core/state/catalog.service';
import { PreferencesService } from '../../../core/state/preferences.service';
import { baseChartOptions, chartPalette } from '../../../core/util/chart-theme';
import { eventStyle } from '../../../core/util/event-style';
import { formatValue } from '../../../core/util/format';
import { EmptyStateComponent } from '../../empty-state/empty-state.component';
import { KpiCardComponent } from '../../kpi-card/kpi-card.component';
import { LockGraphComponent } from '../../lock-graph/lock-graph.component';
import { MonacoEditorComponent } from '../../monaco/monaco-editor.component';
import { StatusTagComponent } from '../../status/status-tag.component';
import { liveCounters, metricCards } from './metrics-view';

export type ResultTab = 'metrics' | 'logs' | 'violations' | 'observations' | 'lockgraph' | 'code' | 'recommendations';

/** Bottom tabs of the lab / detail page. */
@Component({
  selector: 'rl-result-tabs',
  imports: [
    FormsModule,
    RouterLink,
    ScrollingModule,
    TabsModule,
    AccordionModule,
    ChartModule,
    TableModule,
    InputTextModule,
    SelectModule,
    SkeletonModule,
    TooltipModule,
    EmptyStateComponent,
    KpiCardComponent,
    LockGraphComponent,
    MonacoEditorComponent,
    StatusTagComponent,
  ],
  templateUrl: './result-tabs.component.html',
  styleUrl: './result-tabs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResultTabsComponent {
  readonly result = input<ExperimentResult | null>(null);
  readonly events = input<readonly ExperimentEvent[]>([]);
  readonly scenarioId = input<string | null>(null);
  readonly strategy = input<StrategyType | null>(null);
  readonly loading = input(false);
  readonly running = input(false);

  private readonly api = inject(RacelabApi);
  private readonly prefs = inject(PreferencesService);
  protected readonly catalog = inject(CatalogService);

  protected readonly tab = signal<ResultTab>('metrics');
  protected readonly logFilter = signal('');
  protected readonly code = signal<CodeExample | null>(null);
  protected readonly codeLoading = signal(false);
  protected readonly codeError = signal(false);
  protected readonly snippetIndex = signal(0);
  private codeKey: string | null = null;

  protected readonly cards = computed(() => {
    const m = this.result()?.metrics;
    return m ? metricCards(m) : null;
  });
  protected readonly live = computed(() => liveCounters(this.events()));

  protected readonly metricsChart = computed(() => {
    this.prefs.theme();
    const m = this.result()?.metrics;
    if (!m) return null;
    const p = chartPalette();
    return {
      data: {
        labels: ['Committed', 'Rolled back', 'Rejected', 'Conflicts', 'Deadlocks', 'Retries', 'Inconsistencies'],
        datasets: [
          {
            label: 'Count',
            data: [m.committed, m.rolledBack, m.businessRejections, m.conflicts, m.deadlocks, m.retries, m.inconsistencies],
            backgroundColor: [p.green, p.red, p.yellow, p.orange, p.orange, p.yellow, p.orange],
            borderRadius: 4,
          },
        ],
      },
      options: baseChartOptions(p, { legend: false }),
    };
  });

  protected readonly logLines = computed(() => {
    const q = this.logFilter().trim().toLowerCase();
    const lines = this.events().map((e) => ({
      seq: e.sequence,
      tone: eventStyle(e.eventType).tone,
      text: `+${String(e.elapsedTimeMs).padStart(6, ' ')}ms #${String(e.sequence).padStart(4, '0')} ${(e.workerId ?? 'experiment').padEnd(10, ' ')} ${(e.transactionId ?? '').padEnd(10, ' ')} ${e.eventType.padEnd(26, ' ')} ${e.source === 'DATABASE_OBSERVED' ? '[DB] ' : ''}${e.resource ?? ''} ${e.message ?? ''}${e.connectionPid ? ` pid=${e.connectionPid}` : ''}`,
    }));
    return q ? lines.filter((l) => l.text.toLowerCase().includes(q)) : lines;
  });

  protected readonly snippets = computed<CodeSnippet[]>(() => {
    const c = this.code();
    if (!c) return [];
    return [...c.java, ...c.sql];
  });
  protected readonly snippetOptions = computed(() =>
    this.snippets().map((s, i) => ({ label: `${s.language.toUpperCase()} — ${s.title}`, value: i })),
  );
  protected readonly currentSnippet = computed(() => this.snippets()[this.snippetIndex()] ?? null);
  protected readonly sortedRecommendations = computed(() =>
    [...(this.result()?.recommendations ?? [])].sort((a, b) => a.priority - b.priority),
  );

  protected readonly fmt = formatValue;
  protected readonly trackSeq = (_: number, l: { seq: number }) => l.seq;

  constructor() {
    effect(() => {
      if (this.tab() !== 'code') return;
      const scenarioId = this.scenarioId();
      const strategy = this.strategy();
      if (!scenarioId || !strategy) return;
      const key = `${scenarioId}/${strategy}`;
      if (key === this.codeKey) return;
      this.codeKey = key;
      this.codeLoading.set(true);
      this.codeError.set(false);
      this.api
        .codeExample(scenarioId, strategy, { silent: true })
        .pipe(catchError(() => of(null)))
        .subscribe((c) => {
          this.code.set(c);
          this.codeError.set(!c);
          this.snippetIndex.set(0);
          this.codeLoading.set(false);
        });
    });
  }

  protected entries(values: Record<string, unknown> | null | undefined): [string, string][] {
    return Object.entries(values ?? {}).map(([k, v]) => [k, formatValue(v)]);
  }
}
