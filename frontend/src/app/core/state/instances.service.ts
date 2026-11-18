import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, of, switchMap, timer } from 'rxjs';
import { RacelabApi } from '../api/racelab-api.service';
import { InstanceStatus } from '../models/api.models';

export const INSTANCE_POLL_MS = 10_000;

/** Polls GET /api/instances for the top-bar status chip and the Distributed Lab. */
@Injectable({ providedIn: 'root' })
export class InstancesService {
  private readonly api = inject(RacelabApi);
  private readonly destroyRef = inject(DestroyRef);
  private started = false;

  readonly instances = signal<InstanceStatus[]>([]);
  /** null = never answered; false = BFF unreachable. */
  readonly reachable = signal<boolean | null>(null);
  readonly lastUpdated = signal<Date | null>(null);

  readonly primary = computed(() => this.instances().find((i) => i.instanceId === 'primary') ?? this.instances()[0] ?? null);
  readonly availableCount = computed(() => this.instances().filter((i) => i.available).length);
  readonly runningExperiments = computed(() =>
    this.instances().reduce((sum, i) => sum + (i.available && i.info ? i.info.activeExperiments : 0), 0),
  );
  readonly distributedAvailable = computed(() => this.instances().filter((i) => i.available).length > 1);

  start(): void {
    if (this.started) return;
    this.started = true;
    timer(0, INSTANCE_POLL_MS)
      .pipe(
        switchMap(() =>
          this.api.instances({ silent: true }).pipe(catchError(() => of(null))),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((list) => this.apply(list));
  }

  refresh(): void {
    this.api
      .instances({ silent: true })
      .pipe(catchError(() => of(null)))
      .subscribe((list) => this.apply(list));
  }

  private apply(list: InstanceStatus[] | null): void {
    if (list) {
      this.instances.set(list);
      this.reachable.set(true);
    } else {
      this.reachable.set(false);
    }
    this.lastUpdated.set(new Date());
  }
}
