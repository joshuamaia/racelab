import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TableModule } from 'primeng/table';
import { TooltipModule } from 'primeng/tooltip';
import { SkeletonModule } from 'primeng/skeleton';
import { ExperimentResult } from '../../../core/models/api.models';
import { EmptyStateComponent } from '../../empty-state/empty-state.component';
import { StatusTagComponent } from '../../status/status-tag.component';
import { ConsistencyTagComponent } from '../../status/consistency-tag.component';
import { compactJson, presentStateRows, sortTransactions, summarizeStateRows } from './state-presentation';

/** Initial | Current | Expected | Status table plus per-transaction outcomes. */
@Component({
  selector: 'rl-resource-state',
  imports: [TableModule, TooltipModule, SkeletonModule, EmptyStateComponent, StatusTagComponent, ConsistencyTagComponent],
  templateUrl: './resource-state.component.html',
  styles: `
    :host { display: block; }
    .section-title { font-size: .78rem; text-transform: uppercase; letter-spacing: .06em; color: var(--rl-muted); margin-top: .4rem; }
    td.bad { color: var(--rl-red); font-weight: 600; }
    .msg-cell { max-width: 22rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResourceStateComponent {
  readonly result = input<ExperimentResult | null>(null);
  readonly loading = input(false);
  readonly running = input(false);
  readonly showTransactions = input(true);

  protected readonly rows = computed(() => presentStateRows(this.result()?.stateRows));
  protected readonly summary = computed(() => summarizeStateRows(this.rows()));
  protected readonly transactions = computed(() => sortTransactions(this.result()?.transactions));
  protected readonly compact = compactJson;
}
