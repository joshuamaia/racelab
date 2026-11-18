import {
  ExecutionMode,
  ExperimentConfiguration,
  FailureMode,
  ISOLATION_LEVELS,
  ParameterKey,
  Topology,
} from '../models/api.models';

export type FieldKind = 'number' | 'select' | 'boolean' | 'text';

export interface SelectOption<T = string> {
  label: string;
  value: T;
}

export interface ConfigurationField {
  key: ParameterKey;
  label: string;
  kind: FieldKind;
  min?: number;
  max?: number;
  step?: number;
  fractionDigits?: number;
  suffix?: string;
  options?: SelectOption[];
  help: string;
  group: 'Workload' | 'Data' | 'Timing' | 'Transactions' | 'Execution';
}

export const ISOLATION_OPTIONS: SelectOption[] = ISOLATION_LEVELS.map((l) => ({
  label: humanizeEnum(l),
  value: l,
}));

export const EXECUTION_MODE_OPTIONS: SelectOption<ExecutionMode>[] = [
  { label: 'Concurrent', value: 'CONCURRENT' },
  { label: 'Step by step', value: 'STEP_BY_STEP' },
];

export const TOPOLOGY_OPTIONS: SelectOption<Topology>[] = [
  { label: 'Single JVM', value: 'SINGLE_JVM' },
  { label: 'Distributed (2 instances)', value: 'DISTRIBUTED' },
];

export const FAILURE_MODE_OPTIONS: SelectOption<FailureMode>[] = [
  { label: 'None', value: 'NONE' },
  { label: 'Crash after commit', value: 'CRASH_AFTER_COMMIT' },
  { label: 'Crash before commit', value: 'CRASH_BEFORE_COMMIT' },
  { label: 'Relay crash before ack', value: 'RELAY_CRASH_BEFORE_ACK' },
];

/** Field catalogue with the limits defined in API-CONTRACT.md. */
export const CONFIGURATION_FIELDS: readonly ConfigurationField[] = [
  { key: 'threads', label: 'Threads', kind: 'number', min: 1, max: 16, step: 1, group: 'Workload', help: 'Concurrent workers (1..16).' },
  { key: 'transactions', label: 'Transactions', kind: 'number', min: 1, max: 200, step: 1, group: 'Workload', help: 'Total transactions distributed across workers (1..200).' },
  { key: 'initialValue', label: 'Initial value', kind: 'number', min: 0, max: 1_000_000, step: 100, fractionDigits: 2, group: 'Data', help: 'Initial value of each resource (0..1,000,000).' },
  { key: 'operationValue', label: 'Operation value', kind: 'number', min: 0.01, max: 1_000_000, step: 10, fractionDigits: 2, group: 'Data', help: 'Amount applied by each operation (0.01..1,000,000).' },
  { key: 'resourceCount', label: 'Resources', kind: 'number', min: 1, max: 20, step: 1, group: 'Data', help: 'Number of resources involved (1..20).' },
  { key: 'resourceCode', label: 'Resource code', kind: 'text', group: 'Data', help: 'Template / code of the main resource.' },
  { key: 'delayBeforeReadMs', label: 'Delay before read', kind: 'number', min: 0, max: 5000, step: 50, suffix: ' ms', group: 'Timing', help: 'Artificial pause before reading (0..5000 ms).' },
  { key: 'delayAfterReadMs', label: 'Delay after read', kind: 'number', min: 0, max: 5000, step: 50, suffix: ' ms', group: 'Timing', help: 'Artificial pause between read and write (0..5000 ms) — widens the race window.' },
  { key: 'delayBeforeWriteMs', label: 'Delay before write', kind: 'number', min: 0, max: 5000, step: 50, suffix: ' ms', group: 'Timing', help: 'Artificial pause before writing (0..5000 ms).' },
  { key: 'delayBeforeCommitMs', label: 'Delay before commit', kind: 'number', min: 0, max: 5000, step: 50, suffix: ' ms', group: 'Timing', help: 'Artificial pause before commit (0..5000 ms) — keeps locks held longer.' },
  { key: 'isolationLevel', label: 'Isolation level', kind: 'select', options: ISOLATION_OPTIONS, group: 'Transactions', help: 'PostgreSQL transaction isolation level.' },
  { key: 'transactionTimeoutMs', label: 'Transaction timeout', kind: 'number', min: 1000, max: 60000, step: 500, suffix: ' ms', group: 'Transactions', help: 'Transaction timeout (1000..60000 ms).' },
  { key: 'lockTimeoutMs', label: 'Lock timeout', kind: 'number', min: 100, max: 30000, step: 100, suffix: ' ms', group: 'Transactions', help: 'PostgreSQL lock_timeout (100..30000 ms).' },
  { key: 'retries', label: 'Retries', kind: 'number', min: 0, max: 10, step: 1, group: 'Transactions', help: 'Retries after conflicts (0..10).' },
  { key: 'backoffMs', label: 'Backoff', kind: 'number', min: 0, max: 5000, step: 50, suffix: ' ms', group: 'Transactions', help: 'Pause between retries (0..5000 ms).' },
  { key: 'seed', label: 'Seed', kind: 'number', min: 0, max: 2_147_483_647, step: 1, group: 'Execution', help: 'Random seed for reproducible schedules.' },
  { key: 'executionMode', label: 'Execution mode', kind: 'select', options: EXECUTION_MODE_OPTIONS, group: 'Execution', help: 'Concurrent or step-by-step (checkpoints released manually).' },
  { key: 'topology', label: 'Topology', kind: 'select', options: TOPOLOGY_OPTIONS, group: 'Execution', help: 'Single JVM or distributed across two backend instances.' },
  { key: 'coordinated', label: 'Coordinated start', kind: 'boolean', group: 'Execution', help: 'Workers wait on a barrier so they start simultaneously.' },
  { key: 'lockTtlMs', label: 'Lock TTL', kind: 'number', min: 200, max: 30000, step: 100, suffix: ' ms', group: 'Execution', help: 'Distributed lock TTL (200..30000 ms).' },
  { key: 'failureMode', label: 'Failure mode', kind: 'select', options: FAILURE_MODE_OPTIONS, group: 'Execution', help: 'Fault injected during the experiment.' },
  { key: 'stepTimeoutSeconds', label: 'Step timeout', kind: 'number', min: 10, max: 600, step: 10, suffix: ' s', group: 'Execution', help: 'Max time a checkpoint waits in step mode (10..600 s).' },
];

export const FIELD_BY_KEY: ReadonlyMap<ParameterKey, ConfigurationField> = new Map(
  CONFIGURATION_FIELDS.map((f) => [f.key, f]),
);

export type ConfigurationErrors = Partial<Record<ParameterKey, string>>;

/** Validates a configuration against the contract limits. Returns an empty object when valid. */
export function validateConfiguration(config: Partial<ExperimentConfiguration>): ConfigurationErrors {
  const errors: ConfigurationErrors = {};
  for (const field of CONFIGURATION_FIELDS) {
    const value = config[field.key];
    if (value === undefined) continue;
    if (field.kind === 'number') {
      if (value === null || typeof value !== 'number' || Number.isNaN(value)) {
        errors[field.key] = `${field.label} is required`;
        continue;
      }
      if (field.min !== undefined && value < field.min) {
        errors[field.key] = `${field.label} must be ≥ ${field.min}`;
      } else if (field.max !== undefined && value > field.max) {
        errors[field.key] = `${field.label} must be ≤ ${field.max}`;
      } else if (field.fractionDigits === undefined && !Number.isInteger(value)) {
        errors[field.key] = `${field.label} must be an integer`;
      }
    } else if (field.kind === 'select') {
      if (field.options && !field.options.some((o) => o.value === value)) {
        errors[field.key] = `${field.label} has an invalid value`;
      }
    }
  }
  return errors;
}

/** Clamps a numeric value to the field limits. */
export function clampField(key: ParameterKey, value: number): number {
  const field = FIELD_BY_KEY.get(key);
  if (!field || field.kind !== 'number') return value;
  let v = value;
  if (field.min !== undefined) v = Math.max(field.min, v);
  if (field.max !== undefined) v = Math.min(field.max, v);
  return v;
}

/** Returns only the keys that differ from the defaults (partial configuration sent to the API). */
export function diffConfiguration(
  config: ExperimentConfiguration,
  defaults: ExperimentConfiguration | null | undefined,
): Partial<ExperimentConfiguration> {
  if (!defaults) return { ...config };
  const out: Partial<Record<ParameterKey, unknown>> = {};
  for (const key of Object.keys(config) as ParameterKey[]) {
    if (config[key] !== defaults[key]) out[key] = config[key];
  }
  return out as Partial<ExperimentConfiguration>;
}

export function humanizeEnum(value: string | null | undefined): string {
  if (!value) return '';
  return value
    .toLowerCase()
    .split('_')
    .map((w) => (w.length ? w[0].toUpperCase() + w.slice(1) : w))
    .join(' ');
}
