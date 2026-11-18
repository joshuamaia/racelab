import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { LockGraph } from '../../core/models/api.models';
import { EmptyStateComponent } from '../empty-state/empty-state.component';
import { LOCK_GRAPH_SOURCE_LABELS, layoutLockGraph } from './lock-graph-layout';

/** SVG wait-for graph in the style of a deadlock diagram. */
@Component({
  selector: 'rl-lock-graph',
  imports: [EmptyStateComponent],
  templateUrl: './lock-graph.component.html',
  styleUrl: './lock-graph.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LockGraphComponent {
  readonly graph = input<LockGraph | null | undefined>(null);
  readonly emptyMessage = input('No lock dependencies were recorded for this experiment.');

  protected readonly layout = computed(() => {
    const g = this.graph();
    return g && g.nodes.length ? layoutLockGraph(g) : null;
  });
  protected readonly sourceLabel = computed(() => {
    const g = this.graph();
    return g ? LOCK_GRAPH_SOURCE_LABELS[g.source] ?? g.source : '';
  });
  protected readonly sourceTone = computed(() => {
    const s = this.graph()?.source;
    return s === 'RECONSTRUCTED' ? 'neutral' : 'info';
  });
  protected readonly ariaSummary = computed(() => {
    const g = this.graph();
    if (!g) return '';
    return g.edges
      .map((e, i) => `${i + 1}. ${this.labelOf(g, e.from)} ${e.label ?? '→'} ${this.labelOf(g, e.to)}`)
      .join('; ');
  });

  private labelOf(g: LockGraph, id: string): string {
    return g.nodes.find((n) => n.id === id)?.label ?? id;
  }
}
