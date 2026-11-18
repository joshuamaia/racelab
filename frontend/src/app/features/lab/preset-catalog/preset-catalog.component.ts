import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TooltipModule } from 'primeng/tooltip';
import { Difficulty, ExperimentPreset } from '../../../core/models/api.models';
import { EmptyStateComponent } from '../../../shared/empty-state/empty-state.component';

const DIFFICULTY_TONE: Record<Difficulty, string> = { BEGINNER: 'commit', INTERMEDIATE: 'wait', ADVANCED: 'conflict' };

/** Preset list for a lab category (problem, business scenario, expected result). */
@Component({
  selector: 'rl-preset-catalog',
  imports: [TooltipModule, EmptyStateComponent],
  template: `
    <ul class="catalog" role="listbox" aria-label="Experiment presets">
      @for (p of presets(); track p.id) {
        <li
          role="option"
          tabindex="0"
          [attr.aria-selected]="p.id === selectedId()"
          [class.selected]="p.id === selectedId()"
          [attr.data-testid]="'preset-' + p.id"
          (click)="select.emit(p)"
          (keydown.enter)="select.emit(p)"
          (keydown.space)="$event.preventDefault(); select.emit(p)"
        >
          <div class="head">
            <span class="name">{{ p.name }}</span>
            @if (p.demo) {
              <span class="pill tone-info num">demo {{ p.demo }}</span>
            }
            <span [class]="'pill tone-' + tone(p.difficulty)">{{ p.difficulty.toLowerCase() }}</span>
          </div>
          <p class="problem">{{ p.problem }}</p>
          @if (p.id === selectedId()) {
            <dl class="details">
              <dt><i class="pi pi-briefcase" aria-hidden="true"></i> Business scenario</dt>
              <dd>{{ p.businessScenario }}</dd>
              <dt><i class="pi pi-flag" aria-hidden="true"></i> Expected result</dt>
              <dd>{{ p.expectedResult }}</dd>
              @if (p.explanation) {
                <dt><i class="pi pi-info-circle" aria-hidden="true"></i> Why</dt>
                <dd>{{ p.explanation }}</dd>
              }
            </dl>
          }
          @if (p.tags.length) {
            <div class="tags">
              @for (t of p.tags; track t) {
                <span class="tag num">#{{ t }}</span>
              }
            </div>
          }
        </li>
      } @empty {
        <rl-empty-state icon="pi pi-list" title="No presets" message="The catalog has no experiments for this category." />
      }
    </ul>
  `,
  styles: `
    .catalog { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: .4rem; }
    li { border: 1px solid var(--rl-border); border-radius: var(--rl-radius-sm); padding: .55rem .65rem; cursor: pointer; background: var(--rl-panel-2); }
    li:hover { border-color: var(--rl-blue); }
    li.selected { border-color: var(--rl-blue); background: var(--rl-blue-bg); box-shadow: inset 3px 0 0 var(--rl-blue); }
    .head { display: flex; gap: .35rem; align-items: center; flex-wrap: wrap; }
    .name { font-weight: 600; flex: 1; min-width: 8rem; }
    .problem { margin: .3rem 0 0; font-size: .8rem; color: var(--rl-muted); }
    .details { margin: .45rem 0 0; font-size: .8rem; }
    .details dt { color: var(--rl-muted); font-size: .7rem; text-transform: uppercase; letter-spacing: .05em; margin-top: .35rem; }
    .details dd { margin: .1rem 0 0; }
    .tags { display: flex; gap: .3rem; flex-wrap: wrap; margin-top: .35rem; }
    .tag { font-size: .66rem; color: var(--rl-subtle); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PresetCatalogComponent {
  readonly presets = input<ExperimentPreset[]>([]);
  readonly selectedId = input<string | null>(null);
  readonly select = output<ExperimentPreset>();

  protected tone(d: Difficulty): string {
    return DIFFICULTY_TONE[d] ?? 'neutral';
  }
}
