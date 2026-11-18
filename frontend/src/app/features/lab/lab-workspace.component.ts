import { BreakpointObserver } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { MenuItem } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { MenuModule } from 'primeng/menu';
import { SelectModule } from 'primeng/select';
import { SplitterModule } from 'primeng/splitter';
import { SkeletonModule } from 'primeng/skeleton';
import { TooltipModule } from 'primeng/tooltip';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { map } from 'rxjs';
import { diffConfiguration, validateConfiguration } from '../../core/config/configuration-fields';
import {
  Category,
  ExperimentConfiguration,
  ExperimentPreset,
  ExportFormat,
  StrategyType,
} from '../../core/models/api.models';
import { CatalogService } from '../../core/state/catalog.service';
import { ExperimentSession } from '../../core/state/experiment-session.service';
import { PreferencesService } from '../../core/state/preferences.service';
import { CATEGORY_LABELS } from '../../core/util/routes';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { ResourceStateComponent } from '../../shared/experiment/resource-state/resource-state.component';
import { ResultTabsComponent } from '../../shared/experiment/result-tabs/result-tabs.component';
import { RunHeaderComponent } from '../../shared/experiment/run-header/run-header.component';
import { StepPanelComponent } from '../../shared/experiment/step-panel/step-panel.component';
import { TimelineComponent } from '../../shared/experiment/timeline/timeline.component';
import { ConfigFormComponent } from './config-form/config-form.component';
import { InstancePanelComponent } from './instance-panel/instance-panel.component';
import { IsolationMatrixComponent } from './isolation-matrix/isolation-matrix.component';
import { PresetCatalogComponent } from './preset-catalog/preset-catalog.component';

export interface LabPrefill {
  presetId?: string | null;
  scenarioId?: string | null;
  strategy?: StrategyType | null;
  configuration?: Partial<ExperimentConfiguration> | null;
  autorun?: boolean;
}

const DESCRIPTIONS: Record<Category, string> = {
  CONCURRENCY:
    'Race conditions on shared business data: lost updates, overselling, double booking. Run real concurrent transactions against PostgreSQL and watch every read, write, lock and commit.',
  ISOLATION:
    'Isolation phenomena (non-repeatable reads, phantoms, write skew) reproduced — or prevented — by real PostgreSQL isolation levels.',
  LOCKING:
    'Pessimistic and optimistic locking, lock waits, deadlocks (SQLSTATE 40P01), SKIP LOCKED queues and advisory locks, observed in pg_locks.',
  DISTRIBUTED:
    'Two backend instances sharing one database: JVM locks stop working, distributed locks need fencing tokens, idempotency keys and the outbox pattern keep side effects safe.',
};

/** Reusable lab workspace parameterised by category (Concurrency / Isolation / Locking / Distributed). */
@Component({
  selector: 'rl-lab-workspace',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    ButtonModule,
    MenuModule,
    SelectModule,
    SplitterModule,
    SkeletonModule,
    TooltipModule,
    EmptyStateComponent,
    TimelineComponent,
    StepPanelComponent,
    ResourceStateComponent,
    ResultTabsComponent,
    RunHeaderComponent,
    ConfigFormComponent,
    PresetCatalogComponent,
    IsolationMatrixComponent,
    InstancePanelComponent,
  ],
  providers: [ExperimentSession],
  templateUrl: './lab-workspace.component.html',
  styleUrl: './lab-workspace.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LabWorkspaceComponent {
  readonly category = input.required<Category>();
  readonly prefill = input<LabPrefill | null>(null);

  protected readonly catalog = inject(CatalogService);
  protected readonly session = inject(ExperimentSession);
  protected readonly store = this.session.store;
  private readonly prefs = inject(PreferencesService);
  private readonly router = inject(Router);

  protected readonly wide = toSignal(
    inject(BreakpointObserver)
      .observe('(min-width: 1200px)')
      .pipe(map((r) => r.matches)),
    { initialValue: true },
  );

  protected readonly selectedPreset = signal<ExperimentPreset | null>(null);
  protected readonly strategy = signal<StrategyType | null>(null);
  protected readonly config = signal<ExperimentConfiguration | null>(null);
  protected readonly targetInstance = signal('primary');
  protected readonly showMatrix = signal(true);

  protected readonly title = computed(() => `${CATEGORY_LABELS[this.category()]} Lab`);
  protected readonly description = computed(() => DESCRIPTIONS[this.category()]);
  protected readonly presets = computed(() => this.catalog.presets().filter((p) => p.category === this.category()));
  protected readonly scenario = computed(() => this.catalog.scenario(this.selectedPreset()?.scenarioId));
  protected readonly strategyOptions = computed(() => {
    const preset = this.selectedPreset();
    const list = preset?.availableStrategies?.length ? preset.availableStrategies : this.scenario()?.supportedStrategies ?? [];
    return list.map((s) => ({ label: this.catalog.strategyName(s), value: s }));
  });
  protected readonly strategyInfo = computed(() => this.catalog.strategy(this.strategy()));
  protected readonly errors = computed(() => {
    const c = this.config();
    return c ? validateConfiguration(c) : {};
  });
  protected readonly valid = computed(() => Object.keys(this.errors()).length === 0);

  protected readonly running = computed(() => this.store.isActive());
  protected readonly busy = computed(() => this.session.busy() !== null);
  protected readonly canRun = computed(
    () => !!this.selectedPreset() && !!this.strategy() && !!this.config() && this.valid() && !this.running() && !this.busy(),
  );
  protected readonly stepAvailable = computed(() => this.scenario()?.supportsStepMode !== false);

  protected readonly exportItems: MenuItem[] = (['json', 'csv', 'markdown', 'pdf'] as ExportFormat[]).map((f) => ({
    label: f.toUpperCase(),
    icon: f === 'pdf' ? 'pi pi-file-pdf' : f === 'csv' ? 'pi pi-table' : 'pi pi-file',
    command: () => this.session.exportResult(f).subscribe(),
  }));

  private appliedPrefill: LabPrefill | null | undefined = undefined;

  constructor() {
    this.catalog.ensureLoaded().subscribe();

    // initial selection once the catalog is loaded; re-applied whenever the query-param prefill changes
    effect(() => {
      const presets = this.presets();
      const prefill = this.prefill();
      if (!presets.length) return;
      untracked(() => {
        if (prefill !== this.appliedPrefill && !this.running()) {
          this.appliedPrefill = prefill;
          this.applyPrefill(presets, prefill);
        } else if (!this.selectedPreset()) {
          this.choosePreset(presets[0]);
        }
      });
    });
  }

  private applyPrefill(presets: ExperimentPreset[], prefill: LabPrefill | null): void {
    const preset =
      (prefill?.presetId && presets.find((p) => p.id === prefill.presetId)) ||
      (prefill?.scenarioId && presets.find((p) => p.scenarioId === prefill.scenarioId)) ||
      presets[0];
    this.choosePreset(preset);
    if (prefill?.strategy) this.strategy.set(prefill.strategy);
    if (prefill?.configuration) {
      const base = this.config();
      if (base) this.config.set({ ...base, ...prefill.configuration });
    }
    if (prefill?.autorun && this.canRun()) {
      this.run(this.config()?.executionMode === 'STEP_BY_STEP');
    }
  }

  protected choosePreset(preset: ExperimentPreset): void {
    if (this.running()) return;
    this.selectedPreset.set(preset);
    this.strategy.set(preset.strategy);
    this.config.set(this.defaultsFor(preset));
  }

  protected resetConfig(): void {
    const p = this.selectedPreset();
    if (p) this.config.set(this.defaultsFor(p));
  }

  private defaultsFor(preset: ExperimentPreset): ExperimentConfiguration {
    const scenarioDefaults = this.catalog.scenario(preset.scenarioId)?.defaults;
    const base = { ...(scenarioDefaults ?? {}), ...preset.configuration } as ExperimentConfiguration;
    // client-side defaults (Settings) only apply to non-demo presets, so demos stay reproducible
    if (preset.demo === null || preset.demo === undefined) {
      const d = this.prefs.experimentDefaults();
      const merged = { ...base };
      for (const [k, v] of Object.entries(d)) {
        if (v !== null && v !== undefined) (merged as unknown as Record<string, unknown>)[k] = v;
      }
      return merged;
    }
    return base;
  }

  // ---- actions ---------------------------------------------------------------

  protected run(stepByStep: boolean): void {
    const preset = this.selectedPreset();
    const strategy = this.strategy();
    const cfg = this.config();
    if (!preset || !strategy || !cfg || !this.valid()) return;
    const configuration: ExperimentConfiguration = {
      ...cfg,
      executionMode: stepByStep ? 'STEP_BY_STEP' : cfg.executionMode === 'STEP_BY_STEP' ? 'CONCURRENT' : cfg.executionMode,
    };
    const scenario = this.scenario();
    const instance = this.category() === 'DISTRIBUTED' && this.targetInstance() !== 'primary' ? this.targetInstance() : null;
    this.session
      .run(
        {
          presetId: preset.id,
          scenarioId: preset.scenarioId,
          strategy,
          name: `${preset.name} · ${this.catalog.strategyName(strategy)}`,
          configuration: diffConfiguration(configuration, scenario?.defaults),
        },
        instance,
      )
      .subscribe({ error: () => undefined });
  }

  protected nextStep(workerId?: string | null): void {
    this.session.step(workerId ?? this.store.stepState()?.suggestedWorkerId ?? null).subscribe({ error: () => undefined });
  }

  protected resume(): void {
    this.session.resume().subscribe({ error: () => undefined });
  }

  protected cancel(): void {
    this.session.cancel().subscribe({ error: () => undefined });
  }

  protected restart(): void {
    this.session.rerun().subscribe({ error: () => undefined });
  }

  protected compare(): void {
    const preset = this.selectedPreset();
    if (!preset) return;
    const strategies = this.strategyOptions()
      .map((o) => o.value)
      .slice(0, 8);
    const cfg = this.config();
    const configuration = cfg ? diffConfiguration({ ...cfg, executionMode: 'CONCURRENT' }, this.scenario()?.defaults) : {};
    this.router.navigate(['/comparator'], {
      queryParams: {
        scenarioId: preset.scenarioId,
        strategies: strategies.join(','),
        config: JSON.stringify(configuration),
      },
    });
  }
}
