import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** CONSISTENT / INCONSISTENCY tag. null = not evaluated yet. */
@Component({
  selector: 'rl-consistency-tag',
  template: `<span [class]="'pill tone-' + look().tone" [attr.aria-label]="look().aria"
    ><i [class]="look().icon" aria-hidden="true"></i>{{ look().label }}</span
  >`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConsistencyTagComponent {
  readonly consistent = input<boolean | null | undefined>(null);
  protected readonly look = computed(() => {
    const c = this.consistent();
    if (c === true) return { tone: 'commit', icon: 'pi pi-shield', label: 'CONSISTENT', aria: 'Final state consistent' };
    if (c === false)
      return { tone: 'conflict', icon: 'pi pi-exclamation-triangle', label: 'INCONSISTENCY', aria: 'Inconsistency detected' };
    return { tone: 'neutral', icon: 'pi pi-minus', label: 'n/a', aria: 'Consistency not evaluated' };
  });
}
