import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ExperimentStatus, Severity, TxStatus } from '../../core/models/api.models';

type AnyStatus = ExperimentStatus | TxStatus | Severity | string;

interface StatusLook {
  tone: string;
  icon: string;
  label: string;
}

const LOOKS: Record<string, StatusLook> = {
  CREATED: { tone: 'neutral', icon: 'pi pi-circle', label: 'Created' },
  RUNNING: { tone: 'info', icon: 'pi pi-spin pi-spinner', label: 'Running' },
  COMPLETED: { tone: 'commit', icon: 'pi pi-check-circle', label: 'Completed' },
  FAILED: { tone: 'rollback', icon: 'pi pi-times-circle', label: 'Failed' },
  CANCELLED: { tone: 'neutral', icon: 'pi pi-stop-circle', label: 'Cancelled' },
  COMMITTED: { tone: 'commit', icon: 'pi pi-check', label: 'Committed' },
  ROLLED_BACK: { tone: 'rollback', icon: 'pi pi-undo', label: 'Rolled back' },
  REJECTED: { tone: 'wait', icon: 'pi pi-ban', label: 'Rejected' },
  LOW: { tone: 'neutral', icon: 'pi pi-info-circle', label: 'Low' },
  MEDIUM: { tone: 'wait', icon: 'pi pi-exclamation-circle', label: 'Medium' },
  HIGH: { tone: 'conflict', icon: 'pi pi-exclamation-triangle', label: 'High' },
  CRITICAL: { tone: 'rollback', icon: 'pi pi-exclamation-triangle', label: 'Critical' },
};

export function statusLook(status: string | null | undefined): StatusLook {
  if (!status) return { tone: 'neutral', icon: 'pi pi-minus', label: '—' };
  return LOOKS[status] ?? { tone: 'neutral', icon: 'pi pi-circle', label: status };
}

/** Status pill: icon + text + colour (colour is never the only cue). */
@Component({
  selector: 'rl-status-tag',
  template: `<span [class]="'pill tone-' + look().tone" [attr.aria-label]="'Status: ' + look().label"
    ><i [class]="look().icon" aria-hidden="true"></i>{{ look().label }}</span
  >`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusTagComponent {
  readonly status = input<AnyStatus | null | undefined>(null);
  protected readonly look = computed(() => statusLook(this.status()));
}
