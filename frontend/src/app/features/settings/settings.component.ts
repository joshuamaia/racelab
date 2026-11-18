import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { InputNumberModule } from 'primeng/inputnumber';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { Preferences, PreferencesService, ThemeMode } from '../../core/state/preferences.service';

type DefaultKey = keyof Preferences['experimentDefaults'];

interface LimitField {
  key: DefaultKey;
  label: string;
  min: number;
  max: number;
  suffix?: string;
}

export const DEFAULT_LIMIT_FIELDS: readonly LimitField[] = [
  { key: 'threads', label: 'Threads', min: 1, max: 16 },
  { key: 'transactions', label: 'Transactions', min: 1, max: 200 },
  { key: 'retries', label: 'Retries', min: 0, max: 10 },
  { key: 'lockTimeoutMs', label: 'Lock timeout', min: 100, max: 30000, suffix: ' ms' },
  { key: 'stepTimeoutSeconds', label: 'Step timeout', min: 10, max: 600, suffix: ' s' },
];

@Component({
  selector: 'rl-settings',
  imports: [FormsModule, ButtonModule, InputNumberModule, SelectModule, SelectButtonModule, ToggleSwitchModule],
  templateUrl: './settings.component.html',
  styles: `
    .setting { display: grid; grid-template-columns: minmax(12rem, 1fr) minmax(0, 1.4fr); gap: .4rem 1rem; align-items: center; padding: .55rem 0; border-bottom: 1px solid var(--rl-border); }
    .setting:last-child { border-bottom: none; }
    .setting label { font-weight: 500; }
    .setting .hint { grid-column: 1 / -1; }
    @media (max-width: 640px) { .setting { grid-template-columns: 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsComponent {
  protected readonly prefs = inject(PreferencesService);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);

  protected readonly themeOptions: { label: string; value: ThemeMode; icon: string }[] = [
    { label: 'Dark', value: 'dark', icon: 'pi pi-moon' },
    { label: 'Light', value: 'light', icon: 'pi pi-sun' },
  ];
  protected readonly zoomOptions = [0, 0.05, 0.1, 0.25, 0.5, 1, 2, 4].map((z) => ({
    label: z === 0 ? 'Compact (no time spacing)' : `${z} px per ms`,
    value: z,
  }));
  protected readonly limitFields = DEFAULT_LIMIT_FIELDS;
  protected readonly draftDefaults = signal<Preferences['experimentDefaults']>({ ...this.prefs.experimentDefaults() });

  protected set<K extends keyof Preferences>(key: K, value: Preferences[K]): void {
    this.prefs.update({ [key]: value } as Partial<Preferences>);
    this.saved();
  }

  protected setReconnectDelay(value: number | null): void {
    if (value === null || value === undefined) return;
    this.set('sseReconnectDelayMs', Math.min(10000, Math.max(250, Math.round(value))));
  }

  protected defaultValue(key: DefaultKey): number | null {
    return this.draftDefaults()[key] ?? null;
  }

  protected setDefault(field: LimitField, value: number | null): void {
    this.draftDefaults.update((d) => {
      const next = { ...d };
      if (value === null || value === undefined) delete next[field.key];
      else next[field.key] = Math.min(field.max, Math.max(field.min, Math.round(value)));
      return next;
    });
  }

  protected saveDefaults(): void {
    this.prefs.update({ experimentDefaults: { ...this.draftDefaults() } });
    this.saved();
  }

  protected clearDefaults(): void {
    this.draftDefaults.set({});
    this.saveDefaults();
  }

  protected reset(): void {
    this.confirm.confirm({
      header: 'Clear local preferences',
      message: 'Reset theme, sidebar, stream and timeline settings and the default experiment limits stored in this browser?',
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Clear',
      rejectLabel: 'Cancel',
      acceptButtonStyleClass: 'p-button-danger',
      rejectButtonStyleClass: 'p-button-secondary p-button-outlined',
      accept: () => {
        this.prefs.reset();
        this.draftDefaults.set({});
        this.messages.add({ severity: 'info', summary: 'Local preferences cleared', life: 3000 });
      },
    });
  }

  private saved(): void {
    this.messages.add({ severity: 'success', summary: 'Preferences saved', detail: 'Stored in this browser (localStorage).', life: 2000 });
  }
}
