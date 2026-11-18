import { ChangeDetectionStrategy, Component, computed, effect, inject, input, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MenuItem } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { MenuModule } from 'primeng/menu';
import { SkeletonModule } from 'primeng/skeleton';
import { TooltipModule } from 'primeng/tooltip';
import { ExportFormat } from '../../core/models/api.models';
import { CatalogService } from '../../core/state/catalog.service';
import { ExperimentSession } from '../../core/state/experiment-session.service';
import { formatDateTime, prettyJson } from '../../core/util/format';
import { labRoute } from '../../core/util/routes';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { ResourceStateComponent } from '../../shared/experiment/resource-state/resource-state.component';
import { ResultTabsComponent } from '../../shared/experiment/result-tabs/result-tabs.component';
import { RunHeaderComponent } from '../../shared/experiment/run-header/run-header.component';
import { StepPanelComponent } from '../../shared/experiment/step-panel/step-panel.component';
import { TimelineComponent } from '../../shared/experiment/timeline/timeline.component';

/** /experiments/:id — persisted events + replay; attaches SSE when the experiment is still running. */
@Component({
  selector: 'rl-experiment-detail',
  imports: [
    RouterLink,
    ButtonModule,
    MenuModule,
    SkeletonModule,
    TooltipModule,
    EmptyStateComponent,
    ResourceStateComponent,
    ResultTabsComponent,
    RunHeaderComponent,
    StepPanelComponent,
    TimelineComponent,
  ],
  providers: [ExperimentSession],
  templateUrl: './experiment-detail.component.html',
  styles: `
    .detail-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(320px, 400px); gap: 1rem; align-items: start; }
    @media (max-width: 1200px) { .detail-grid { grid-template-columns: 1fr; } }
    .side { display: flex; flex-direction: column; gap: 1rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExperimentDetailComponent {
  /** Bound from the :id route param (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly session = inject(ExperimentSession);
  protected readonly store = this.session.store;
  protected readonly catalog = inject(CatalogService);
  private readonly router = inject(Router);

  protected readonly notFound = computed(() => !this.store.details() && this.session.error() !== null);
  protected readonly running = computed(() => this.store.isActive());
  protected readonly configJson = computed(() => prettyJson(this.store.details()?.configuration ?? {}));
  protected readonly fmtDate = formatDateTime;

  protected readonly exportItems: MenuItem[] = (['json', 'csv', 'markdown', 'pdf'] as ExportFormat[]).map((f) => ({
    label: f.toUpperCase(),
    command: () => this.session.exportResult(f).subscribe(),
  }));

  constructor() {
    this.catalog.ensureLoaded().subscribe();
    effect(() => {
      const id = this.id();
      untracked(() => this.session.attach(id).subscribe({ error: () => undefined }));
    });
  }

  protected rerun(): void {
    this.session.rerun().subscribe({
      next: (d) => this.router.navigate(['/experiments', d.id]),
      error: () => undefined,
    });
  }

  protected cancel(): void {
    this.session.cancel().subscribe({ error: () => undefined });
  }

  protected step(workerId: string | null): void {
    this.session.step(workerId).subscribe({ error: () => undefined });
  }

  protected resume(): void {
    this.session.resume().subscribe({ error: () => undefined });
  }

  protected openInLab(): void {
    const d = this.store.details();
    if (!d) return;
    this.router.navigate([labRoute(d.category)], {
      queryParams: {
        preset: d.presetId ?? undefined,
        scenarioId: d.scenarioId,
        strategy: d.strategy,
        config: JSON.stringify(d.configuration),
      },
    });
  }
}
