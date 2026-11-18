import { compactJson, presentStateRows, sortTransactions, summarizeStateRows } from './state-presentation';
import { TransactionOutcome } from '../../../core/models/api.models';

describe('result presentation', () => {
  const rows = presentStateRows([
    { resource: 'account:ACC-A', label: 'Conta A', attribute: 'balance', initial: '1000.00', actual: '200.00', expected: '-600.00', ok: false },
    { resource: 'account:ACC-B', label: '', attribute: 'balance', initial: '50.00', actual: '50.00', expected: '50.00', ok: true },
    { resource: 'stock:SKU', label: 'SKU', attribute: 'qty', initial: null, actual: '', expected: null, ok: true },
  ]);

  it('maps state rows to Initial | Current | Expected | Status', () => {
    expect(rows[0]).toMatchObject({ label: 'Conta A', initial: '1000.00', current: '200.00', expected: '-600.00', statusLabel: 'INVÁLIDA' });
    expect(rows[1]).toMatchObject({ label: 'account:ACC-B', statusLabel: 'OK' });
    expect(rows[2]).toMatchObject({ initial: '—', current: '—', expected: '—' });
    expect(new Set(rows.map((r) => r.key)).size).toBe(3);
  });

  it('summarizes OK vs invalid rows', () => {
    expect(summarizeStateRows(rows)).toEqual({ ok: 2, invalid: 1 });
    expect(presentStateRows(null)).toEqual([]);
  });

  it('formats before/after values and sorts transactions naturally', () => {
    expect(compactJson({ balance: 1000, version: 0 })).toBe('balance=1000, version=0');
    expect(compactJson(null)).toBe('—');
    const tx = (workerId: string, transactionId: string) => ({ workerId, transactionId }) as TransactionOutcome;
    const sorted = sortTransactions([tx('worker-10', 'tx-1'), tx('worker-2', 'tx-2'), tx('worker-2', 'tx-1')]);
    expect(sorted.map((t) => `${t.workerId}/${t.transactionId}`)).toEqual(['worker-2/tx-1', 'worker-2/tx-2', 'worker-10/tx-1']);
  });
});
