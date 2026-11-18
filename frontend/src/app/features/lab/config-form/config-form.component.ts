import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AccordionModule } from 'primeng/accordion';
import { ButtonModule } from 'primeng/button';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TooltipModule } from 'primeng/tooltip';
import {
  CONFIGURATION_FIELDS,
  ConfigurationErrors,
  ConfigurationField,
  SelectOption,
  clampField,
} from '../../../core/config/configuration-fields';
import { ExperimentConfiguration, ParameterKey, ScenarioDescriptor } from '../../../core/models/api.models';

export interface FieldView extends ConfigurationField {
  displayLabel: string;
  inputId: string;
  selectOptions: SelectOption<unknown>[];
}

/** Splits fields into the scenario's relevant parameters and the "Advanced" rest. */
export function partitionFields(scenario: ScenarioDescriptor | null | undefined): { main: FieldView[]; advanced: FieldView[] } {
  const relevant = new Set<ParameterKey>(scenario?.relevantParameters ?? []);
  const labels = scenario?.parameterLabels ?? {};
  const views = CONFIGURATION_FIELDS.map<FieldView>((f) => {
    let selectOptions: SelectOption<unknown>[] = f.options ?? [];
    if (f.key === 'executionMode' && scenario && !scenario.supportsStepMode) {
      selectOptions = selectOptions.filter((o) => o.value !== 'STEP_BY_STEP');
    }
    if (f.key === 'topology' && scenario && !scenario.supportsDistributed) {
      selectOptions = selectOptions.filter((o) => o.value !== 'DISTRIBUTED');
    }
    return { ...f, displayLabel: labels[f.key] ?? f.label, inputId: `cfg-${f.key}`, selectOptions };
  });
  if (!relevant.size) return { main: views, advanced: [] };
  return {
    main: views.filter((v) => relevant.has(v.key)),
    advanced: views.filter((v) => !relevant.has(v.key)),
  };
}

/** Experiment configuration editor with the contract's min/max limits enforced. */
@Component({
  selector: 'rl-config-form',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    AccordionModule,
    ButtonModule,
    InputNumberModule,
    InputTextModule,
    SelectModule,
    ToggleSwitchModule,
    TooltipModule,
  ],
  templateUrl: './config-form.component.html',
  styleUrl: './config-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfigFormComponent {
  readonly config = input.required<ExperimentConfiguration>();
  readonly scenario = input<ScenarioDescriptor | null | undefined>(null);
  readonly errors = input<ConfigurationErrors>({});
  readonly disabled = input(false);
  readonly configChange = output<ExperimentConfiguration>();
  readonly reset = output<void>();

  protected readonly fields = computed(() => partitionFields(this.scenario()));
  protected readonly templateOptions = computed(() =>
    (this.scenario()?.templates ?? []).map((t) => ({ label: `${t.code} — ${t.label}`, value: t.code })),
  );

  protected err(key: ParameterKey): string | undefined {
    return this.errors()[key];
  }

  protected value(key: ParameterKey): unknown {
    return this.config()[key];
  }

  protected set(key: ParameterKey, raw: unknown): void {
    let v = raw;
    if (typeof raw === 'number') v = clampField(key, raw);
    this.configChange.emit({ ...this.config(), [key]: v } as ExperimentConfiguration);
  }

  protected num(key: ParameterKey): number | null {
    const v = this.config()[key];
    return typeof v === 'number' ? v : null;
  }
}
