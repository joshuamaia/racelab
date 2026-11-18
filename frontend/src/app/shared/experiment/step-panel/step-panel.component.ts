import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';
import { StepState } from '../../../core/models/api.models';
import { EmptyStateComponent } from '../../empty-state/empty-state.component';

/** Step-by-step controls: pending checkpoints per worker, blocked workers, suggested next, deadline countdown. */
@Component({
  selector: 'rl-step-panel',
  imports: [ButtonModule, TooltipModule, EmptyStateComponent],
  templateUrl: './step-panel.component.html',
  styleUrl: './step-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StepPanelComponent {
  readonly state = input<StepState | null>(null);
  readonly busy = input(false);
  readonly release = output<string | null>();
  readonly resume = output<void>();

  private readonly now = signal(Date.now());

  protected readonly remainingSeconds = computed(() => {
    const deadline = this.state()?.deadline;
    if (!deadline) return null;
    const ms = new Date(deadline).getTime() - this.now();
    return Number.isNaN(ms) ? null : Math.max(0, Math.round(ms / 1000));
  });

  constructor() {
    const t = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(t));
  }
}
