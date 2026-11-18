import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter } from 'rxjs';
import { ButtonModule } from 'primeng/button';
import { ToastModule } from 'primeng/toast';
import { TooltipModule } from 'primeng/tooltip';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { PreferencesService } from './core/state/preferences.service';
import { InstancesService } from './core/state/instances.service';
import { CatalogService } from './core/state/catalog.service';

interface NavItem {
  label: string;
  icon: string;
  route: string;
  section?: string;
}

export const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Dashboard', icon: 'pi pi-chart-bar', route: '/dashboard', section: 'Overview' },
  { label: 'Concurrency Lab', icon: 'pi pi-bolt', route: '/lab/concurrency', section: 'Labs' },
  { label: 'Isolation Lab', icon: 'pi pi-clone', route: '/lab/isolation' },
  { label: 'Locking Lab', icon: 'pi pi-lock', route: '/lab/locking' },
  { label: 'Distributed Lab', icon: 'pi pi-sitemap', route: '/lab/distributed' },
  { label: 'Strategy Comparator', icon: 'pi pi-sliders-h', route: '/comparator', section: 'Analysis' },
  { label: 'Transaction Inspector', icon: 'pi pi-search', route: '/inspector' },
  { label: 'Code Explorer', icon: 'pi pi-code', route: '/code' },
  { label: 'Experiment History', icon: 'pi pi-history', route: '/history' },
  { label: 'Documentation', icon: 'pi pi-book', route: '/docs', section: 'Help' },
  { label: 'Settings', icon: 'pi pi-cog', route: '/settings' },
];

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ButtonModule, ToastModule, TooltipModule, ConfirmDialogModule],
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App implements OnInit {
  protected readonly prefs = inject(PreferencesService);
  protected readonly instances = inject(InstancesService);
  private readonly catalog = inject(CatalogService);
  private readonly router = inject(Router);

  protected readonly nav = NAV_ITEMS;
  protected readonly mobileOpen = signal(false);
  protected readonly collapsed = this.prefs.sidebarCollapsed;

  protected readonly statusChip = computed(() => {
    const reachable = this.instances.reachable();
    const list = this.instances.instances();
    if (reachable === null) return { tone: 'neutral', icon: 'pi pi-spin pi-spinner', text: 'Connecting…', tip: 'Checking backend instances' };
    if (!reachable) return { tone: 'rollback', icon: 'pi pi-times-circle', text: 'API offline', tip: 'The BFF (/api) is not reachable' };
    const up = list.filter((i) => i.available);
    if (!up.length) return { tone: 'rollback', icon: 'pi pi-times-circle', text: 'Backend down', tip: 'No backend instance is available' };
    const pg = up[0].info?.postgresVersion ?? '';
    const tip = list.map((i) => `${i.instanceId}: ${i.available ? 'up' : 'down'}`).join(' · ') + (pg ? ` — ${pg}` : '');
    return {
      tone: up.length === list.length ? 'commit' : 'wait',
      icon: up.length === list.length ? 'pi pi-check-circle' : 'pi pi-exclamation-triangle',
      text: `${up.length}/${list.length} instance${list.length > 1 ? 's' : ''} up`,
      tip,
    };
  });

  constructor() {
    this.router.events
      .pipe(
        filter((e) => e instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.mobileOpen.set(false));
  }

  ngOnInit(): void {
    this.instances.start();
    this.catalog.ensureLoaded().subscribe();
  }

  protected toggleSidebar(): void {
    if (window.matchMedia('(max-width: 900px)').matches) this.mobileOpen.update((v) => !v);
    else this.prefs.toggleSidebar();
  }
}
