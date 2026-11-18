import { LockGraph } from '../../core/models/api.models';
import { layoutLockGraph } from './lock-graph-layout';

describe('lock graph layout', () => {
  const graph: LockGraph = {
    source: 'DATABASE_REPORTED',
    description: 'deadlock',
    cycle: true,
    nodes: [
      { id: 'pid:1', kind: 'TRANSACTION', label: 'worker-1 (pid 1)', workerId: 'worker-1', pid: 1, victim: false },
      { id: 'lock:a', kind: 'LOCK', label: 'row ACC-A', workerId: null, pid: null, victim: false },
      { id: 'pid:2', kind: 'TRANSACTION', label: 'worker-2 (pid 2)', workerId: 'worker-2', pid: 2, victim: true },
    ],
    edges: [
      { from: 'pid:1', to: 'lock:a', label: 'waits for ShareLock' },
      { from: 'lock:a', to: 'pid:2', label: 'held by' },
      { from: 'pid:2', to: 'pid:1', label: 'waits for' },
      { from: 'pid:2', to: 'missing', label: 'ignored' },
    ],
  };

  it('places every node and numbers edges, skipping edges to unknown nodes', () => {
    const l = layoutLockGraph(graph);
    expect(l.nodes.length).toBe(3);
    expect(l.edges.map((e) => e.index)).toEqual([1, 2, 3]);
    expect(l.nodes.find((n) => n.node.victim)?.node.id).toBe('pid:2');
    for (const n of l.nodes) {
      expect(n.x).toBeGreaterThan(0);
      expect(n.y).toBeGreaterThan(0);
      expect(n.x).toBeLessThan(l.width);
      expect(n.y).toBeLessThan(l.height);
    }
  });

  it('uses a two-column layout for large graphs', () => {
    const nodes = Array.from({ length: 12 }, (_, i) => ({
      id: `n${i}`,
      kind: (i % 2 ? 'LOCK' : 'TRANSACTION') as 'LOCK' | 'TRANSACTION',
      label: `n${i}`,
      workerId: null,
      pid: null,
      victim: false,
    }));
    const l = layoutLockGraph({ ...graph, nodes, edges: [] });
    const xs = new Set(l.nodes.map((n) => Math.round(n.x)));
    expect(xs.size).toBe(2);
  });
});
