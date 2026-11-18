import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';
import { Category, ExperimentConfiguration, StrategyType } from '../../core/models/api.models';
import { LabPrefill, LabWorkspaceComponent } from './lab-workspace.component';

/** Parses lab query params (?preset=&scenarioId=&strategy=&config=<json>&autorun=1). */
export function parseLabPrefill(params: Record<string, string | undefined>): LabPrefill | null {
  const prefill: LabPrefill = {};
  if (params['preset']) prefill.presetId = params['preset'];
  if (params['scenarioId']) prefill.scenarioId = params['scenarioId'];
  if (params['strategy']) prefill.strategy = params['strategy'] as StrategyType;
  if (params['config']) {
    try {
      const parsed: unknown = JSON.parse(params['config']);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        prefill.configuration = parsed as Partial<ExperimentConfiguration>;
      }
    } catch {
      /* ignore malformed configuration */
    }
  }
  if (params['autorun'] === '1' || params['autorun'] === 'true') prefill.autorun = true;
  return Object.keys(prefill).length ? prefill : null;
}

/** Route wrapper: reads the category from route data and the prefill from query params. */
@Component({
  selector: 'rl-lab-page',
  imports: [LabWorkspaceComponent],
  template: `<rl-lab-workspace [category]="category()" [prefill]="prefill()" />`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LabPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly data = toSignal(this.route.data, { initialValue: this.route.snapshot.data });
  protected readonly category = computed(() => (this.data()['category'] as Category) ?? 'CONCURRENCY');
  protected readonly prefill = toSignal(
    this.route.queryParams.pipe(map((p) => parseLabPrefill(p as Record<string, string | undefined>))),
    { initialValue: parseLabPrefill(this.route.snapshot.queryParams as Record<string, string | undefined>) },
  );
}
