import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';
import { ExperimentStore } from '../../../core/state/experiment-store';
import { CatalogService } from '../../../core/state/catalog.service';
import { formatMs } from '../../../core/util/format';
import { StatusTagComponent } from '../../status/status-tag.component';
import { ConsistencyTagComponent } from '../../status/consistency-tag.component';

const CONNECTION_LOOK: Record<string, { tone: string; icon: string; label: string }> = {
  idle: { tone: 'neutral', icon: 'pi pi-circle', label: 'stream idle' },
  connecting: { tone: 'wait', icon: 'pi pi-spin pi-spinner', label: 'connecting' },
  open: { tone: 'commit', icon: 'pi pi-wifi', label: 'live' },
  reconnecting: { tone: 'wait', icon: 'pi pi-spin pi-refresh', label: 'reconnecting' },
  closed: { tone: 'neutral', icon: 'pi pi-check', label: 'stream closed' },
  failed: { tone: 'rollback', icon: 'pi pi-exclamation-circle', label: 'stream failed' },
};

/** Compact status bar for the current experiment (id, status, consistency, SSE connection). */
@Component({
  selector: 'rl-run-header',
  imports: [RouterLink, ButtonModule, TooltipModule, StatusTagComponent, ConsistencyTagComponent],
  template: `
    @if (store().details(); as d) {
      <div class="run-header" role="status" aria-live="polite">
        <a class="num run-id" [routerLink]="['/experiments', d.id]" pTooltip="Open experiment detail / replay">{{ d.id }}</a>
        <rl-status-tag [status]="d.status" />
        @if (store().isTerminal()) {
          <rl-consistency-tag [consistent]="store().result()?.consistent ?? d.consistent" />
        }
        <span [class]="'pill tone-' + conn().tone" [pTooltip]="connTip()">
          <i [class]="conn().icon" aria-hidden="true"></i>{{ conn().label }}
        </span>
        @if (store().connection() === 'failed') {
          <p-button label="Reconnect" icon="pi pi-refresh" size="small" [text]="true" (onClick)="reconnect.emit()" />
        }
        <span class="muted small ellipsis">{{ catalog.strategyName(d.strategy) }} · {{ d.executionMode === 'STEP_BY_STEP' ? 'step-by-step' : 'concurrent' }}</span>
        @if (d.instanceId) {
          <span class="pill tone-neutral num"><i class="pi pi-server" aria-hidden="true"></i>{{ d.instanceId }}</span>
        }
        <span class="grow"></span>
        <span class="num small muted">{{ store().events().length }} events</span>
        @if (d.durationMs !== null) {
          <span class="num small muted">{{ duration() }}</span>
        }
      </div>
      @if (d.errorMessage) {
        <div class="callout tone-rollback small"><i class="pi pi-times-circle" aria-hidden="true"></i>{{ d.errorMessage }}</div>
      }
    }
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .run-header { display: flex; align-items: center; gap: .45rem; flex-wrap: wrap; min-width: 0; }
    .run-id { font-weight: 600; }
    .callout { margin-top: .4rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunHeaderComponent {
  readonly store = input.required<ExperimentStore>();
  readonly reconnect = output<void>();
  protected readonly catalog = inject(CatalogService);

  protected readonly conn = computed(() => CONNECTION_LOOK[this.store().connection()] ?? CONNECTION_LOOK['idle']);
  protected readonly connTip = computed(() => {
    const s = this.store();
    const hb = s.lastHeartbeat();
    const attempt = s.reconnectAttempt();
    return `Server-Sent Events ${s.connection()}${attempt ? ` (attempt ${attempt})` : ''}${hb ? ` · last heartbeat ${hb.toLocaleTimeString()}` : ''}`;
  });
  protected readonly duration = computed(() => formatMs(this.store().details()?.durationMs ?? null));
}
