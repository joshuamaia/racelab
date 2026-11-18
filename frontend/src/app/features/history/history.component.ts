import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ConfirmationService, MenuItem, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { MenuModule } from 'primeng/menu';
import { SelectModule } from 'primeng/select';
import { SkeletonModule } from 'primeng/skeleton';
import { TableModule } from 'primeng/table';
import { TooltipModule } from 'primeng/tooltip';
import type { TableLazyLoadEvent } from 'primeng/types/table';
import { catchError, forkJoin, of } from 'rxjs';
import { RacelabApi } from '../../core/api/racelab-api.service';
import { humanizeEnum } from '../../core/config/configuration-fields';
import {
  CATEGORIES,
  Category,
  EXPERIMENT_STATUSES,
  ExperimentDetails,
  ExperimentStatus,
  ExportFormat,
  Metrics,
  StrategyType,
} from '../../core/models/api.models';
import { CatalogService } from '../../core/state/catalog.service';
import { downloadBlob, filenameFromDisposition } from '../../core/util/download';
import { formatDateTime, formatMs } from '../../core/util/format';
import { CATEGORY_LABELS, labRoute } from '../../core/util/routes';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { ConsistencyTagComponent } from '../../shared/status/consistency-tag.component';
import { StatusTagComponent } from '../../shared/status/status-tag.component';
import { METRIC_ROWS, MetricRow } from './metric-rows';

export const PAGE_SIZES = [10, 20, 50];

@Component({
  selector: 'rl-history',
  imports: [
    FormsModule,
    RouterLink,
    ButtonModule,
    DialogModule,
    MenuModule,
    SelectModule,
    SkeletonModule,
    TableModule,
    TooltipModule,
    EmptyStateComponent,
    ConsistencyTagComponent,
    StatusTagComponent,
  ],
  templateUrl: './history.component.html',
  styleUrl: './history.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HistoryComponent implements OnInit {
  private readonly api = inject(RacelabApi);
  private readonly router = inject(Router);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  protected readonly catalog = inject(CatalogService);

  protected readonly rows = signal<ExperimentDetails[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(false);
  protected readonly first = signal(0);
  protected readonly pageSize = signal(20);
  protected readonly pageSizes = PAGE_SIZES;
  protected readonly selection = signal<ExperimentDetails[]>([]);
  protected readonly busyId = signal<string | null>(null);

  protected readonly category = signal<Category | null>(null);
  protected readonly status = signal<ExperimentStatus | null>(null);
  protected readonly scenarioId = signal<string | null>(null);
  protected readonly strategy = signal<StrategyType | null>(null);

  protected readonly categoryOptions = CATEGORIES.map((c) => ({ label: CATEGORY_LABELS[c], value: c }));
  protected readonly statusOptions = EXPERIMENT_STATUSES.map((s) => ({ label: humanizeEnum(s), value: s }));
  protected readonly scenarioOptions = computed(() =>
    this.catalog
      .scenarios()
      .filter((s) => !this.category() || s.category === this.category())
      .map((s) => ({ label: s.name, value: s.id })),
  );
  protected readonly strategyOptions = computed(() => this.catalog.strategies().map((s) => ({ label: s.name, value: s.id })));
  protected readonly hasFilters = computed(() => !!(this.category() || this.status() || this.scenarioId() || this.strategy()));

  // export menu (shared by all rows)
  private exportTarget: ExperimentDetails | null = null;
  protected readonly exportItems: MenuItem[] = (['json', 'csv', 'markdown', 'pdf'] as ExportFormat[]).map((f) => ({
    label: f.toUpperCase(),
    icon: f === 'pdf' ? 'pi pi-file-pdf' : f === 'csv' ? 'pi pi-table' : 'pi pi-file',
    command: () => this.export(f),
  }));

  // compare dialog
  protected readonly compareVisible = signal(false);
  protected readonly compareLoading = signal(false);
  protected readonly compared = signal<{ experiment: ExperimentDetails; metrics: Metrics | null }[]>([]);
  protected readonly metricRows: readonly MetricRow[] = METRIC_ROWS;
  protected readonly canCompare = computed(() => this.selection().length >= 2 && this.selection().length <= 4);

  protected readonly formatMs = formatMs;
  protected readonly formatDateTime = formatDateTime;

  ngOnInit(): void {
    this.catalog.ensureLoaded().subscribe();
  }

  protected onLazy(event: TableLazyLoadEvent): void {
    this.first.set(event.first ?? 0);
    if (event.rows) this.pageSize.set(event.rows);
    this.load();
  }

  protected applyFilters(): void {
    this.first.set(0);
    this.selection.set([]);
    this.load();
  }

  protected setCategory(c: Category | null): void {
    this.category.set(c);
    const sc = this.catalog.scenario(this.scenarioId());
    if (c && sc && sc.category !== c) this.scenarioId.set(null);
    this.applyFilters();
  }

  protected clearFilters(): void {
    this.category.set(null);
    this.status.set(null);
    this.scenarioId.set(null);
    this.strategy.set(null);
    this.applyFilters();
  }

  protected load(): void {
    this.loading.set(true);
    const size = this.pageSize();
    this.api
      .listExperiments({
        page: Math.floor(this.first() / size),
        size,
        category: this.category(),
        status: this.status(),
        scenarioId: this.scenarioId(),
        strategy: this.strategy(),
      })
      .pipe(catchError(() => of(null)))
      .subscribe((page) => {
        this.rows.set(page?.content ?? []);
        this.total.set(page?.totalElements ?? 0);
        this.loading.set(false);
      });
  }

  protected details(e: ExperimentDetails): void {
    this.router.navigate(['/experiments', e.id]);
  }

  protected rerun(e: ExperimentDetails): void {
    this.busyId.set(e.id);
    this.api.rerun(e.id, true, { instance: e.instanceId }).subscribe({
      next: (created) => {
        this.busyId.set(null);
        this.messages.add({ severity: 'success', summary: 'Rerun started', detail: `${e.id} → ${created.id}` });
        this.router.navigate(['/experiments', created.id]);
      },
      error: () => this.busyId.set(null),
    });
  }

  protected duplicate(e: ExperimentDetails): void {
    this.router.navigate([labRoute(e.category)], {
      queryParams: {
        scenarioId: e.scenarioId,
        strategy: e.strategy,
        preset: e.presetId ?? undefined,
        config: JSON.stringify(e.configuration),
      },
    });
  }

  protected openExport(event: Event, e: ExperimentDetails, menu: { toggle: (ev: Event) => void }): void {
    this.exportTarget = e;
    menu.toggle(event);
  }

  private export(format: ExportFormat): void {
    const e = this.exportTarget;
    if (!e) return;
    this.api.exportExperiment(e.id, format).subscribe((res) => {
      const ext = format === 'markdown' ? 'md' : format;
      if (res.body) downloadBlob(res.body, filenameFromDisposition(res.headers.get('Content-Disposition'), `${e.id}.${ext}`));
    });
  }

  protected remove(e: ExperimentDetails): void {
    this.confirm.confirm({
      header: 'Delete experiment',
      message: `Delete ${e.id} (${e.name})? Its history and its namespaced data are removed permanently.`,
      icon: 'pi pi-trash',
      acceptLabel: 'Delete',
      rejectLabel: 'Cancel',
      acceptButtonStyleClass: 'p-button-danger',
      rejectButtonStyleClass: 'p-button-secondary p-button-outlined',
      accept: () => {
        this.busyId.set(e.id);
        this.api.deleteExperiment(e.id).subscribe({
          next: () => {
            this.busyId.set(null);
            this.selection.update((s) => s.filter((x) => x.id !== e.id));
            this.messages.add({ severity: 'success', summary: 'Experiment deleted', detail: e.id });
            this.load();
          },
          error: () => this.busyId.set(null),
        });
      },
    });
  }

  protected openCompare(): void {
    const list = this.selection();
    if (list.length < 2) return;
    this.compareVisible.set(true);
    this.compareLoading.set(true);
    forkJoin(list.map((e) => this.api.metrics(e.id, { silent: true }).pipe(catchError(() => of(null))))).subscribe((metrics) => {
      this.compared.set(list.map((experiment, i) => ({ experiment, metrics: metrics[i] })));
      this.compareLoading.set(false);
    });
  }

  protected metricValue(m: Metrics | null, row: MetricRow): string {
    return m ? row.format(m) : '—';
  }

  protected comparatorQuery(): Record<string, string> {
    const list = this.selection();
    const scenarios = new Set(list.map((e) => e.scenarioId));
    const strategies = [...new Set(list.map((e) => e.strategy))];
    return scenarios.size === 1
      ? { scenarioId: list[0].scenarioId, strategies: strategies.join(','), config: JSON.stringify(list[0].configuration) }
      : {};
  }
}
