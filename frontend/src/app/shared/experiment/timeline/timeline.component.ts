import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { MultiSelectModule } from 'primeng/multiselect';
import { SelectModule } from 'primeng/select';
import { SliderModule } from 'primeng/slider';
import { TooltipModule } from 'primeng/tooltip';
import { MenuModule } from 'primeng/menu';
import { MenuItem } from 'primeng/api';
import { EVENT_TYPES, EventType, ExperimentEvent } from '../../../core/models/api.models';
import { PreferencesService } from '../../../core/state/preferences.service';
import { downloadText } from '../../../core/util/download';
import { EventTone, TONE_LABELS, eventLabel, eventStyle } from '../../../core/util/event-style';
import { formatMs, prettyJson } from '../../../core/util/format';
import {
  EXPERIMENT_LANE_ID,
  LockMark,
  TimelineRow,
  buildLanes,
  buildRows,
  eventsToCsv,
  filterEvents,
} from '../../../core/util/timeline';
import { EmptyStateComponent } from '../../empty-state/empty-state.component';
import { REPLAY_SPEEDS, ReplayController } from './replay-controller';

export const ZOOM_LEVELS = [0, 0.05, 0.1, 0.25, 0.5, 1, 2, 4] as const;

/**
 * Concurrency-simulator style timeline: one column per worker (+ an experiment header lane),
 * rows ordered by elapsed time with a ms gutter and time-proportional spacing.
 */
@Component({
  selector: 'rl-timeline',
  imports: [FormsModule, ButtonModule, MultiSelectModule, SelectModule, SliderModule, TooltipModule, MenuModule, EmptyStateComponent],
  templateUrl: './timeline.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TimelineComponent {
  readonly events = input<readonly ExperimentEvent[]>([]);
  readonly live = input(false);
  readonly experimentId = input<string | null>(null);
  readonly height = input('520px');

  private readonly prefs = inject(PreferencesService);
  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');

  protected readonly zoom = signal<number>(this.prefs.timelineZoom());
  protected readonly zoomLevels = ZOOM_LEVELS;
  protected readonly workerFilter = signal<string[]>([]);
  protected readonly txFilter = signal<string[]>([]);
  protected readonly typeFilter = signal<EventType[]>([]);
  protected readonly highlightConflicts = signal(false);
  protected readonly follow = signal(true);
  protected readonly expanded = signal<ReadonlySet<number>>(new Set());
  protected readonly replay = new ReplayController();
  protected readonly speeds = REPLAY_SPEEDS.map((s) => ({ label: `${s}×`, value: s }));
  protected readonly tones = Object.entries(TONE_LABELS) as [EventTone, string][];

  protected readonly typeOptions = EVENT_TYPES.map((t) => ({ label: eventLabel(t), value: t }));

  /** All lanes (stable columns even during replay). */
  private readonly allLanes = computed(() => buildLanes(this.events()));
  protected readonly workerOptions = computed(() =>
    this.allLanes()
      .filter((l) => l.kind === 'worker')
      .map((l) => ({ label: l.label, value: l.id })),
  );
  protected readonly txOptions = computed(() => {
    const set = new Set<string>();
    for (const e of this.events()) if (e.transactionId) set.add(e.transactionId);
    return [...set].sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).map((t) => ({ label: t, value: t }));
  });

  private readonly filter = computed(() => ({
    workers: this.workerFilter(),
    transactionIds: this.txFilter(),
    types: this.typeFilter(),
  }));
  protected readonly lanes = computed(() => buildLanes(filterEvents(this.events(), this.filter())));
  private readonly visibleSource = computed(() => {
    if (this.replay.active()) {
      this.replay.position();
      return this.replay.visible();
    }
    return this.events();
  });
  protected readonly rows = computed<TimelineRow[]>(() =>
    buildRows(filterEvents(this.visibleSource(), this.filter()), this.lanes(), this.zoom()),
  );
  protected readonly lastElapsed = computed(() => {
    const r = this.rows();
    return r.length ? r[r.length - 1].event.elapsedTimeMs : 0;
  });
  protected readonly conflictCount = computed(() => this.rows().filter((r) => r.conflict).length);
  protected readonly observedCount = computed(() => this.rows().filter((r) => r.event.source === 'DATABASE_OBSERVED').length);
  protected readonly gridColumns = computed(() => `var(--tl-gutter) repeat(${Math.max(1, this.lanes().length)}, var(--tl-lane-w))`);
  protected readonly hasFilters = computed(
    () => this.workerFilter().length > 0 || this.txFilter().length > 0 || this.typeFilter().length > 0,
  );

  protected readonly exportItems: MenuItem[] = [
    { label: 'Timeline as JSON', icon: 'pi pi-file', command: () => this.export('json') },
    { label: 'Timeline as CSV', icon: 'pi pi-table', command: () => this.export('csv') },
  ];

  protected readonly formatMs = formatMs;
  protected readonly style = eventStyle;
  protected readonly experimentLaneId = EXPERIMENT_LANE_ID;

  constructor() {
    afterRenderEffect(() => {
      this.rows();
      const el = this.scroller()?.nativeElement;
      if (!el || !untracked(() => this.follow()) || !(untracked(() => this.live()) || untracked(() => this.replay.active()))) return;
      el.scrollTop = el.scrollHeight;
    });
    inject(DestroyRef).onDestroy(() => this.replay.exit());
  }

  protected setZoom(value: number): void {
    this.zoom.set(value);
  }

  protected zoomIn(): void {
    const idx = ZOOM_LEVELS.findIndex((z) => z >= this.zoom());
    this.zoom.set(ZOOM_LEVELS[Math.min(ZOOM_LEVELS.length - 1, idx + 1)]);
  }

  protected zoomOut(): void {
    const idx = ZOOM_LEVELS.findIndex((z) => z >= this.zoom());
    this.zoom.set(ZOOM_LEVELS[Math.max(0, idx - 1)]);
  }

  protected toggleExpanded(seq: number): void {
    this.expanded.update((set) => {
      const next = new Set(set);
      if (next.has(seq)) next.delete(seq);
      else next.add(seq);
      return next;
    });
  }

  protected isExpanded(seq: number): boolean {
    return this.expanded().has(seq);
  }

  protected clearFilters(): void {
    this.workerFilter.set([]);
    this.txFilter.set([]);
    this.typeFilter.set([]);
  }

  protected startReplay(): void {
    this.replay.begin(this.events());
    this.follow.set(true);
  }

  protected lockMarks(row: TimelineRow): { lane: number; mark: Exclude<LockMark, null> }[] {
    const out: { lane: number; mark: Exclude<LockMark, null> }[] = [];
    row.locks.forEach((m, i) => {
      if (m) out.push({ lane: i, mark: m });
    });
    return out;
  }

  protected details(e: ExperimentEvent): string {
    return prettyJson(e.details ?? {});
  }

  protected hasDetails(e: ExperimentEvent): boolean {
    return !!e.details && Object.keys(e.details).length > 0;
  }

  protected onScroll(el: HTMLElement): void {
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
    if (this.follow() !== nearBottom) this.follow.set(nearBottom);
  }

  protected export(format: 'json' | 'csv'): void {
    const events = filterEvents(this.events(), this.filter());
    const base = `${this.experimentId() ?? 'experiment'}-timeline`;
    if (format === 'json') downloadText(JSON.stringify(events, null, 2), `${base}.json`, 'application/json');
    else downloadText(eventsToCsv(events), `${base}.csv`, 'text/csv');
  }
}
