import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CatalogService } from '../../core/state/catalog.service';
import { labRoute } from '../../core/util/routes';
import { ExperimentPreset } from '../../core/models/api.models';

interface TocEntry {
  id: string;
  label: string;
  children?: { id: string; label: string }[];
}

export const DOCS_TOC: readonly TocEntry[] = [
  {
    id: 'concepts',
    label: 'Concepts',
    children: [
      { id: 'race-condition', label: 'Race condition' },
      { id: 'lost-update', label: 'Lost update' },
      { id: 'deadlock', label: 'Deadlock' },
      { id: 'write-skew', label: 'Write skew' },
      { id: 'isolation-levels', label: 'Isolation levels' },
      { id: 'optimistic-pessimistic', label: 'Optimistic vs pessimistic' },
      { id: 'atomic-update', label: 'Atomic update' },
      { id: 'advisory-locks', label: 'Advisory locks' },
      { id: 'redis-locks', label: 'Redis locks & fencing' },
      { id: 'idempotency', label: 'Idempotency' },
      { id: 'outbox', label: 'Transactional outbox' },
    ],
  },
  { id: 'reading', label: 'Reading the timeline' },
  { id: 'demos', label: 'Demo walkthroughs' },
  { id: 'architecture', label: 'Architecture' },
  { id: 'limitations', label: 'Limitations' },
];

@Component({
  selector: 'rl-docs',
  imports: [RouterLink],
  templateUrl: './docs.component.html',
  styleUrl: './docs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocsComponent implements OnInit {
  private readonly document = inject(DOCUMENT);
  protected readonly catalog = inject(CatalogService);
  protected readonly toc = DOCS_TOC;
  protected readonly active = signal('concepts');

  ngOnInit(): void {
    this.catalog.ensureLoaded().subscribe();
  }

  protected go(id: string): void {
    this.active.set(id);
    this.document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  protected labLink(p: ExperimentPreset): string {
    return labRoute(p.category);
  }
}
