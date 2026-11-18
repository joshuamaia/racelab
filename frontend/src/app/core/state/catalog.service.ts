import { Injectable, computed, inject, signal } from '@angular/core';
import { forkJoin, Observable, of, shareReplay, tap, map, catchError } from 'rxjs';
import { RacelabApi } from '../api/racelab-api.service';
import {
  Category,
  ExperimentPreset,
  ScenarioDescriptor,
  StrategyDescriptor,
  StrategyType,
} from '../models/api.models';
import { humanizeEnum } from '../config/configuration-fields';

/** Cached catalog data (presets, scenarios, strategies) shared by every page. */
@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly api = inject(RacelabApi);

  readonly presets = signal<ExperimentPreset[]>([]);
  readonly scenarios = signal<ScenarioDescriptor[]>([]);
  readonly strategies = signal<StrategyDescriptor[]>([]);
  readonly loaded = signal(false);
  readonly failed = signal(false);

  readonly demos = computed(() =>
    this.presets()
      .filter((p) => p.demo !== null && p.demo !== undefined)
      .sort((a, b) => (a.demo ?? 0) - (b.demo ?? 0)),
  );

  private readonly strategyMap = computed(() => new Map(this.strategies().map((s) => [s.id, s])));
  private readonly scenarioMap = computed(() => new Map(this.scenarios().map((s) => [s.id, s])));
  private load$: Observable<boolean> | null = null;

  /** Loads once; subsequent calls reuse the cached result (failed loads may be retried). */
  ensureLoaded(): Observable<boolean> {
    if (!this.load$) {
      this.load$ = forkJoin({
        presets: this.api.catalog(),
        scenarios: this.api.scenarios(),
        strategies: this.api.strategies(),
      }).pipe(
        tap(({ presets, scenarios, strategies }) => {
          this.presets.set(presets);
          this.scenarios.set(scenarios);
          this.strategies.set(strategies);
          this.loaded.set(true);
          this.failed.set(false);
        }),
        map(() => true),
        catchError(() => {
          this.failed.set(true);
          this.load$ = null;
          return of(false);
        }),
        shareReplay(1),
      );
    }
    return this.load$;
  }

  presetsFor(category: Category): ExperimentPreset[] {
    return this.presets().filter((p) => p.category === category);
  }

  scenariosFor(category: Category): ScenarioDescriptor[] {
    return this.scenarios().filter((s) => s.category === category);
  }

  scenario(id: string | null | undefined): ScenarioDescriptor | undefined {
    return id ? this.scenarioMap().get(id) : undefined;
  }

  preset(id: string | null | undefined): ExperimentPreset | undefined {
    return id ? this.presets().find((p) => p.id === id) : undefined;
  }

  strategy(id: StrategyType | null | undefined): StrategyDescriptor | undefined {
    return id ? this.strategyMap().get(id) : undefined;
  }

  strategyName(id: StrategyType | null | undefined): string {
    if (!id) return '—';
    return this.strategyMap().get(id)?.name ?? humanizeEnum(id);
  }

  scenarioName(id: string | null | undefined): string {
    if (!id) return '—';
    return this.scenarioMap().get(id)?.name ?? id;
  }
}
