import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { ChartModule } from 'primeng/chart';
import { SkeletonModule } from 'primeng/skeleton';
import { TableModule } from 'primeng/table';
import { TooltipModule } from 'primeng/tooltip';
import { catchError, of } from 'rxjs';
import { RacelabApi } from '../../core/api/racelab-api.service';
import { Dashboard, ExperimentPreset } from '../../core/models/api.models';
import { CatalogService } from '../../core/state/catalog.service';
import { PreferencesService } from '../../core/state/preferences.service';
import { baseChartOptions, chartPalette } from '../../core/util/chart-theme';
import { formatDateTime, formatMs, formatNumber, formatPercent } from '../../core/util/format';
import { CATEGORY_LABELS, labRoute } from '../../core/util/routes';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { KpiCardComponent } from '../../shared/kpi-card/kpi-card.component';
import { ConsistencyTagComponent } from '../../shared/status/consistency-tag.component';
import { StatusTagComponent } from '../../shared/status/status-tag.component';

interface Kpi {
  label: string;
  value: string;
  icon: string;
  tone: string;
  hint?: string;
}

@Component({
  selector: 'rl-dashboard',
  imports: [
    RouterLink,
    ButtonModule,
    ChartModule,
    SkeletonModule,
    TableModule,
    TooltipModule,
    EmptyStateComponent,
    KpiCardComponent,
    ConsistencyTagComponent,
    StatusTagComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardComponent implements OnInit {
  private readonly api = inject(RacelabApi);
  private readonly router = inject(Router);
  private readonly prefs = inject(PreferencesService);
  protected readonly catalog = inject(CatalogService);

  protected readonly data = signal<Dashboard | null>(null);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);

  protected readonly isEmpty = computed(() => (this.data()?.totals.experiments ?? 0) === 0);

  protected readonly kpis = computed<Kpi[]>(() => {
    const t = this.data()?.totals;
    if (!t) return [];
    return [
      { label: 'Experiments', value: formatNumber(t.experiments), icon: 'pi pi-flask', tone: 'read' },
      { label: 'Transactions', value: formatNumber(t.transactions), icon: 'pi pi-arrow-right-arrow-left', tone: 'info' },
      { label: 'Inconsistencies', value: formatNumber(t.inconsistencies), icon: 'pi pi-exclamation-circle', tone: t.inconsistencies ? 'conflict' : 'commit' },
      { label: 'Deadlocks', value: formatNumber(t.deadlocks), icon: 'pi pi-sync', tone: 'conflict' },
      { label: 'Rollbacks', value: formatNumber(t.rollbacks), icon: 'pi pi-undo', tone: 'rollback' },
      { label: 'Version conflicts', value: formatNumber(t.versionConflicts), icon: 'pi pi-exclamation-triangle', tone: 'conflict' },
      { label: 'Avg duration', value: formatMs(t.avgDurationMs, 1), icon: 'pi pi-clock', tone: 'read' },
      { label: 'Success rate', value: formatPercent(t.successRate), icon: 'pi pi-check-circle', tone: 'commit', hint: 'completed / finished experiment runs' },
      { label: 'Running', value: formatNumber(t.running), icon: 'pi pi-spin pi-cog', tone: 'info' },
    ];
  });

  protected readonly charts = computed(() => {
    this.prefs.theme();
    const d = this.data();
    if (!d) return null;
    const p = chartPalette();
    const name = (s: Parameters<CatalogService['strategyName']>[0]) => this.catalog.strategyName(s);
    const doughnutOpts = {
      maintainAspectRatio: false,
      responsive: true,
      plugins: { legend: { position: 'right' as const, labels: { color: p.text, boxWidth: 12, font: { size: 11 } } } },
    };
    return {
      category: {
        data: {
          labels: d.byCategory.map((c) => CATEGORY_LABELS[c.category] ?? c.category),
          datasets: [{ data: d.byCategory.map((c) => c.count), backgroundColor: p.series, borderColor: p.grid }],
        },
        options: doughnutOpts,
      },
      duration: {
        data: {
          labels: d.durationByStrategy.map((s) => name(s.strategy)),
          datasets: [{ label: 'Avg duration (ms)', data: d.durationByStrategy.map((s) => s.avgMs), backgroundColor: p.blue, borderRadius: 4 }],
        },
        options: baseChartOptions(p, { legend: false }),
      },
      failure: {
        data: {
          labels: d.failureRateByStrategy.map((s) => name(s.strategy)),
          datasets: [
            {
              label: 'Failure rate (%)',
              data: d.failureRateByStrategy.map((s) => +(s.rate * 100).toFixed(2)),
              backgroundColor: p.red,
              borderRadius: 4,
            },
          ],
        },
        options: baseChartOptions(p, { legend: false, percent: true }),
      },
      overTime: {
        data: {
          labels: d.inconsistenciesOverTime.map((b) => formatDateTime(b.bucket)),
          datasets: [
            { label: 'Experiments', data: d.inconsistenciesOverTime.map((b) => b.experiments), borderColor: p.blue, backgroundColor: p.blue, tension: 0.25 },
            {
              label: 'Inconsistencies',
              data: d.inconsistenciesOverTime.map((b) => b.inconsistencies),
              borderColor: p.orange,
              backgroundColor: p.orange,
              tension: 0.25,
            },
          ],
        },
        options: baseChartOptions(p),
      },
    };
  });

  protected readonly formatMs = formatMs;
  protected readonly formatDateTime = formatDateTime;

  ngOnInit(): void {
    this.catalog.ensureLoaded().subscribe();
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.api
      .dashboard()
      .pipe(catchError(() => of(null)))
      .subscribe((d) => {
        this.data.set(d);
        this.failed.set(!d);
        this.loading.set(false);
      });
  }

  protected runDemo(p: ExperimentPreset): void {
    this.router.navigate([labRoute(p.category)], { queryParams: { preset: p.id, autorun: 1 } });
  }

  protected difficultyTone(d: string): string {
    return d === 'BEGINNER' ? 'commit' : d === 'INTERMEDIATE' ? 'wait' : 'conflict';
  }
}
