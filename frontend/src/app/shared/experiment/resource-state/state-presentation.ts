import { StateRow, TransactionOutcome } from '../../../core/models/api.models';

export interface StateRowView {
  key: string;
  resource: string;
  label: string;
  attribute: string;
  initial: string;
  current: string;
  expected: string;
  ok: boolean;
  statusLabel: 'OK' | 'INVÁLIDA';
}

const dash = (v: string | null | undefined) => (v === null || v === undefined || v === '' ? '—' : v);

/** Maps result.stateRows to the Initial | Current | Expected | Status table. */
export function presentStateRows(rows: readonly StateRow[] | null | undefined): StateRowView[] {
  return (rows ?? []).map((r, i) => ({
    key: `${r.resource}:${r.attribute}:${i}`,
    resource: r.resource,
    label: r.label || r.resource,
    attribute: r.attribute,
    initial: dash(r.initial),
    current: dash(r.actual),
    expected: dash(r.expected),
    ok: r.ok,
    statusLabel: r.ok ? 'OK' : 'INVÁLIDA',
  }));
}

export function summarizeStateRows(rows: readonly StateRowView[]): { ok: number; invalid: number } {
  const invalid = rows.filter((r) => !r.ok).length;
  return { ok: rows.length - invalid, invalid };
}

export function compactJson(value: Record<string, unknown> | null | undefined): string {
  if (!value || !Object.keys(value).length) return '—';
  return Object.entries(value)
    .map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
    .join(', ');
}

export function sortTransactions(list: readonly TransactionOutcome[] | null | undefined): TransactionOutcome[] {
  return [...(list ?? [])].sort(
    (a, b) =>
      a.workerId.localeCompare(b.workerId, undefined, { numeric: true }) ||
      a.transactionId.localeCompare(b.transactionId, undefined, { numeric: true }),
  );
}
