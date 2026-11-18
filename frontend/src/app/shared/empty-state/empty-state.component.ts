import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'rl-empty-state',
  template: `
    <div class="empty-state" role="status">
      <i [class]="icon()" aria-hidden="true"></i>
      <div class="empty-title">{{ title() }}</div>
      @if (message()) {
        <div class="small">{{ message() }}</div>
      }
      <ng-content />
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmptyStateComponent {
  readonly icon = input('pi pi-inbox');
  readonly title = input.required<string>();
  readonly message = input<string | null>(null);
}
