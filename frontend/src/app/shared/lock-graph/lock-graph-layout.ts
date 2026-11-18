import { LockGraph, LockGraphNode } from '../../core/models/api.models';

export interface PlacedNode {
  node: LockGraphNode;
  x: number;
  y: number;
  /** half width / half height of the shape */
  hw: number;
  hh: number;
  lines: string[];
}

export interface PlacedEdge {
  index: number;
  from: PlacedNode;
  to: PlacedNode;
  label: string;
  path: string;
  labelX: number;
  labelY: number;
}

export interface GraphLayout {
  width: number;
  height: number;
  nodes: PlacedNode[];
  edges: PlacedEdge[];
}

const TX_HW = 84;
const TX_HH = 30;
const LOCK_HW = 78;
const LOCK_HH = 24;
const PAD = 70;

function wrap(label: string, max = 22): string[] {
  const words = label.split(/\s+/);
  const lines: string[] = [];
  let cur = '';
  for (const w of words) {
    if ((cur + ' ' + w).trim().length > max && cur) {
      lines.push(cur);
      cur = w;
    } else cur = (cur + ' ' + w).trim();
  }
  if (cur) lines.push(cur);
  return lines.slice(0, 3);
}

function boundary(n: PlacedNode, dx: number, dy: number): { x: number; y: number } {
  if (dx === 0 && dy === 0) return { x: n.x, y: n.y };
  let t: number;
  if (n.node.kind === 'TRANSACTION') {
    t = 1 / Math.sqrt((dx / n.hw) ** 2 + (dy / n.hh) ** 2);
  } else {
    t = Math.min(dx !== 0 ? n.hw / Math.abs(dx) : Infinity, dy !== 0 ? n.hh / Math.abs(dy) : Infinity);
  }
  return { x: n.x + dx * t, y: n.y + dy * t };
}

/**
 * Deadlock-diagram style layout: small graphs on a circle (cycles read naturally),
 * larger graphs in two columns (transactions left, locks right).
 */
export function layoutLockGraph(graph: LockGraph): GraphLayout {
  const count = graph.nodes.length;
  const placed: PlacedNode[] = [];
  let width: number;
  let height: number;

  const make = (node: LockGraphNode, x: number, y: number): PlacedNode => ({
    node,
    x,
    y,
    hw: node.kind === 'TRANSACTION' ? TX_HW : LOCK_HW,
    hh: node.kind === 'TRANSACTION' ? TX_HH : LOCK_HH,
    lines: wrap(node.label),
  });

  if (count <= 10) {
    const radius = count <= 1 ? 0 : Math.max(130, (count * 190) / (2 * Math.PI));
    width = radius * 2 + TX_HW * 2 + PAD * 2;
    height = radius * 2 + TX_HH * 2 + PAD * 2;
    const cx = width / 2;
    const cy = height / 2;
    graph.nodes.forEach((node, i) => {
      const angle = -Math.PI / 2 + (2 * Math.PI * i) / Math.max(1, count);
      placed.push(make(node, cx + radius * Math.cos(angle), cy + radius * Math.sin(angle)));
    });
  } else {
    const txs = graph.nodes.filter((n) => n.kind === 'TRANSACTION');
    const locks = graph.nodes.filter((n) => n.kind !== 'TRANSACTION');
    const rows = Math.max(txs.length, locks.length);
    width = 2 * (TX_HW * 2) + 260 + PAD * 2;
    height = rows * 90 + PAD * 2;
    txs.forEach((n, i) => placed.push(make(n, PAD + TX_HW, PAD + 45 + i * 90)));
    locks.forEach((n, i) => placed.push(make(n, width - PAD - LOCK_HW, PAD + 45 + i * 90)));
  }

  const byId = new Map(placed.map((p) => [p.node.id, p]));
  const pairs = new Set(graph.edges.map((e) => `${e.from}→${e.to}`));
  const edges: PlacedEdge[] = [];
  graph.edges.forEach((e, i) => {
    const from = byId.get(e.from);
    const to = byId.get(e.to);
    if (!from || !to) return;
    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const start = boundary(from, dx, dy);
    const end = boundary(to, -dx, -dy);
    const bidirectional = pairs.has(`${e.to}→${e.from}`);
    let path: string;
    let lx: number;
    let ly: number;
    if (from === to) {
      path = `M ${from.x + from.hw * 0.6} ${from.y - from.hh} c 40 -60 80 0 ${from.hw * 0.4} ${from.hh * 0.8}`;
      lx = from.x + from.hw + 30;
      ly = from.y - from.hh - 20;
    } else if (bidirectional) {
      const len = Math.hypot(dx, dy) || 1;
      const ox = (-dy / len) * 28;
      const oy = (dx / len) * 28;
      const mx = (start.x + end.x) / 2 + ox;
      const my = (start.y + end.y) / 2 + oy;
      path = `M ${start.x} ${start.y} Q ${mx} ${my} ${end.x} ${end.y}`;
      lx = (start.x + end.x) / 4 + mx / 2;
      ly = (start.y + end.y) / 4 + my / 2;
    } else {
      path = `M ${start.x} ${start.y} L ${end.x} ${end.y}`;
      lx = (start.x + end.x) / 2;
      ly = (start.y + end.y) / 2;
    }
    edges.push({ index: i + 1, from, to, label: e.label ?? '', path, labelX: lx, labelY: ly });
  });

  return { width: Math.max(width, 320), height: Math.max(height, 200), nodes: placed, edges };
}

export const LOCK_GRAPH_SOURCE_LABELS: Record<LockGraph['source'], string> = {
  DATABASE_REPORTED: 'Reported by PostgreSQL',
  DATABASE_OBSERVED: 'Observed in PostgreSQL (pg_locks / pg_blocking_pids)',
  RECONSTRUCTED: 'Reconstructed from application events',
};
