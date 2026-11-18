import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'rl-kpi-card',
  template: `
    <div [class]="'kpi tone-' + tone()" role="group" [attr.aria-label]="label() + ': ' + value()">
      <div class="kpi-label"><i [class]="icon()" aria-hidden="true"></i>{{ label() }}</div>
      <div class="kpi-value">{{ value() }}</div>
      @if (hint()) {
        <div class="kpi-hint">{{ hint() }}</div>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KpiCardComponent {
  readonly label = input.required<string>();
  readonly value = input.required<string>();
  readonly icon = input('pi pi-chart-line');
  readonly tone = input('read');
  readonly hint = input<string | null>(null);
}
