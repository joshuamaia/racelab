import { CONFIG } from '../../../testing/fixtures';
import { partitionFields } from '../../features/lab/config-form/config-form.component';
import { ScenarioDescriptor } from '../models/api.models';
import { clampField, diffConfiguration, validateConfiguration } from './configuration-fields';

describe('configuration validation', () => {
  it('accepts the contract example configuration', () => {
    expect(validateConfiguration(CONFIG)).toEqual({});
  });

  it.each([
    ['threads', 0],
    ['threads', 17],
    ['transactions', 0],
    ['transactions', 201],
    ['resourceCount', 21],
    ['delayAfterReadMs', 5001],
    ['delayBeforeReadMs', -1],
    ['transactionTimeoutMs', 999],
    ['transactionTimeoutMs', 60001],
    ['lockTimeoutMs', 99],
    ['retries', 11],
    ['backoffMs', 5001],
    ['lockTtlMs', 199],
    ['stepTimeoutSeconds', 601],
    ['initialValue', -1],
    ['initialValue', 1_000_001],
    ['operationValue', 0],
  ] as const)('rejects %s = %s', (key, value) => {
    const errors = validateConfiguration({ ...CONFIG, [key]: value });
    expect(errors[key]).toBeDefined();
  });

  it('accepts boundary values', () => {
    const errors = validateConfiguration({
      ...CONFIG,
      threads: 16,
      transactions: 200,
      resourceCount: 20,
      delayBeforeCommitMs: 5000,
      transactionTimeoutMs: 60000,
      lockTimeoutMs: 100,
      retries: 10,
      lockTtlMs: 30000,
      stepTimeoutSeconds: 10,
      operationValue: 0.01,
      initialValue: 1_000_000,
    });
    expect(errors).toEqual({});
  });

  it('rejects non-integer counts but allows decimal money values', () => {
    expect(validateConfiguration({ threads: 2.5 }).threads).toContain('integer');
    expect(validateConfiguration({ operationValue: 10.25 })).toEqual({});
  });

  it('rejects null numbers and invalid enums', () => {
    expect(validateConfiguration({ threads: null as unknown as number }).threads).toBeDefined();
    expect(validateConfiguration({ isolationLevel: 'CHAOS' as never }).isolationLevel).toBeDefined();
  });

  it('clamps values to the limits', () => {
    expect(clampField('threads', 99)).toBe(16);
    expect(clampField('threads', -3)).toBe(1);
    expect(clampField('retries', 5)).toBe(5);
  });

  it('diffs against defaults to send a partial configuration', () => {
    expect(diffConfiguration({ ...CONFIG, threads: 8 }, CONFIG)).toEqual({ threads: 8 });
    expect(diffConfiguration(CONFIG, null)).toEqual(CONFIG);
  });
});

describe('partitionFields', () => {
  const scenario = {
    relevantParameters: ['threads', 'initialValue'],
    parameterLabels: { initialValue: 'Initial balance (BRL)' },
    supportsStepMode: false,
    supportsDistributed: false,
  } as unknown as ScenarioDescriptor;

  it('shows relevant parameters first and the rest under Advanced, with scenario labels', () => {
    const { main, advanced } = partitionFields(scenario);
    expect(main.map((f) => f.key)).toEqual(['threads', 'initialValue']);
    expect(main[1].displayLabel).toBe('Initial balance (BRL)');
    expect(advanced.some((f) => f.key === 'retries')).toBe(true);
  });

  it('hides unsupported execution modes and topologies', () => {
    const { advanced } = partitionFields(scenario);
    const mode = advanced.find((f) => f.key === 'executionMode');
    const topology = advanced.find((f) => f.key === 'topology');
    expect(mode?.selectOptions.map((o) => o.value)).toEqual(['CONCURRENT']);
    expect(topology?.selectOptions.map((o) => o.value)).toEqual(['SINGLE_JVM']);
  });
});
