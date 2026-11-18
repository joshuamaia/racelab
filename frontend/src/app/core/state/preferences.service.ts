import { DOCUMENT } from '@angular/common';
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { ExperimentConfiguration } from '../models/api.models';

export type ThemeMode = 'dark' | 'light';

export interface Preferences {
  theme: ThemeMode;
  sidebarCollapsed: boolean;
  sseReconnectDelayMs: number;
  timelineZoom: number;
  /** Client-side defaults applied on top of scenario defaults when opening a lab. */
  experimentDefaults: Partial<Pick<ExperimentConfiguration, 'threads' | 'transactions' | 'retries' | 'lockTimeoutMs' | 'stepTimeoutSeconds'>>;
}

export const DEFAULT_PREFERENCES: Preferences = {
  theme: 'dark',
  sidebarCollapsed: false,
  sseReconnectDelayMs: 1000,
  timelineZoom: 0.5,
  experimentDefaults: {},
};

export const PREFERENCES_KEY = 'racelab.preferences';
export const DARK_CLASS = 'app-dark';

function readStorage(): Partial<Preferences> {
  try {
    const raw = localStorage.getItem(PREFERENCES_KEY);
    return raw ? (JSON.parse(raw) as Partial<Preferences>) : {};
  } catch {
    return {};
  }
}

@Injectable({ providedIn: 'root' })
export class PreferencesService {
  private readonly document = inject(DOCUMENT);
  private readonly state = signal<Preferences>({ ...DEFAULT_PREFERENCES, ...readStorage() });

  readonly preferences = this.state.asReadonly();
  readonly theme = computed(() => this.state().theme);
  readonly sidebarCollapsed = computed(() => this.state().sidebarCollapsed);
  readonly sseReconnectDelayMs = computed(() => this.state().sseReconnectDelayMs);
  readonly timelineZoom = computed(() => this.state().timelineZoom);
  readonly experimentDefaults = computed(() => this.state().experimentDefaults);

  constructor() {
    effect(() => {
      const prefs = this.state();
      this.document.documentElement.classList.toggle(DARK_CLASS, prefs.theme === 'dark');
      this.document.documentElement.style.colorScheme = prefs.theme;
      try {
        localStorage.setItem(PREFERENCES_KEY, JSON.stringify(prefs));
      } catch {
        /* storage unavailable: keep in-memory preferences */
      }
    });
  }

  update(patch: Partial<Preferences>): void {
    this.state.update((s) => ({ ...s, ...patch }));
  }

  toggleTheme(): void {
    this.update({ theme: this.theme() === 'dark' ? 'light' : 'dark' });
  }

  toggleSidebar(): void {
    this.update({ sidebarCollapsed: !this.sidebarCollapsed() });
  }

  reset(): void {
    try {
      localStorage.removeItem(PREFERENCES_KEY);
    } catch {
      /* ignore */
    }
    this.state.set({ ...DEFAULT_PREFERENCES });
  }
}
