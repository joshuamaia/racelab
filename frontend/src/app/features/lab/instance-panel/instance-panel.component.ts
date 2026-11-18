import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TooltipModule } from 'primeng/tooltip';
import { InstancesService } from '../../../core/state/instances.service';

/** Backend instances + target-instance selector for the Distributed Lab. */
@Component({
  selector: 'rl-instance-panel',
  imports: [FormsModule, ButtonModule, SelectButtonModule, TooltipModule],
  template: `
    <div class="inst-grid">
      @for (i of instances.instances(); track i.instanceId) {
        <div class="inst" [class.down]="!i.available">
          <div class="row">
            <i class="pi pi-server" aria-hidden="true"></i>
            <strong class="num">{{ i.instanceId }}</strong>
            <span [class]="'pill tone-' + (i.available ? 'commit' : 'rollback')">
              <i [class]="i.available ? 'pi pi-check-circle' : 'pi pi-times-circle'" aria-hidden="true"></i>{{ i.available ? 'available' : 'unavailable' }}
            </span>
          </div>
          <div class="small muted num ellipsis">{{ i.url }}</div>
          @if (i.info; as info) {
            <div class="small num">v{{ info.applicationVersion }} · Java {{ info.javaVersion }} · {{ info.activeExperiments }} running</div>
            <div class="small">
              Redis:
              <span [class]="'pill tone-' + (info.redisAvailable ? 'commit' : 'rollback')">{{ info.redisAvailable ? 'up' : 'down' }}</span>
            </div>
          }
        </div>
      } @empty {
        <div class="muted small">No instance information (GET /api/instances).</div>
      }
      <div class="inst target">
        <label class="small muted" id="target-label">Target instance (X-RaceLab-Instance)</label>
        <p-selectbutton
          [options]="options()"
          [ngModel]="target()"
          (ngModelChange)="targetChange.emit($event)"
          optionLabel="label"
          optionValue="value"
          optionDisabled="disabled"
          [allowEmpty]="false"
          ariaLabelledBy="target-label"
          size="small"
        />
        <p-button label="Refresh" icon="pi pi-refresh" size="small" [text]="true" (onClick)="instances.refresh()" />
      </div>
    </div>
    @if (!instances.distributedAvailable()) {
      <div class="callout tone-wait small" role="note">
        <i class="pi pi-info-circle" aria-hidden="true"></i>
        <span>
          The distributed profile is not running (only one backend instance is available). Start it with
          <code>docker compose --profile distributed up</code> to run experiments across two JVMs. Single-JVM strategies
          (synchronized, ReentrantLock) only protect one instance — with two instances they no longer prevent races, which
          is exactly what this lab demonstrates. Redis-based strategies also require Redis.
        </span>
      </div>
    }
  `,
  styles: `
    .inst-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: .6rem; }
    .inst { border: 1px solid var(--rl-border); border-radius: var(--rl-radius-sm); padding: .55rem .7rem; display: flex; flex-direction: column; gap: .25rem; background: var(--rl-panel-2); min-width: 0; }
    .inst.down { opacity: .75; border-style: dashed; }
    .callout { margin-top: .6rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InstancePanelComponent {
  protected readonly instances = inject(InstancesService);
  readonly target = input<string>('primary');
  readonly targetChange = output<string>();

  protected readonly options = computed(() => {
    const list = this.instances.instances();
    if (!list.length) return [{ label: 'primary', value: 'primary', disabled: false }];
    return list.map((i) => ({ label: i.instanceId, value: i.instanceId, disabled: !i.available }));
  });
}
