/** Reads RaceLab CSS tokens so Chart.js follows the active theme. */
export interface ChartPalette {
  text: string;
  muted: string;
  grid: string;
  series: string[];
  green: string;
  red: string;
  orange: string;
  yellow: string;
  blue: string;
  cyan: string;
  purple: string;
}

function cssVar(name: string, fallback: string): string {
  if (typeof document === 'undefined') return fallback;
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

export function chartPalette(): ChartPalette {
  const blue = cssVar('--rl-blue', '#60a5fa');
  const cyan = cssVar('--rl-cyan', '#22d3ee');
  const purple = cssVar('--rl-purple', '#c084fc');
  const green = cssVar('--rl-green', '#4ade80');
  const orange = cssVar('--rl-orange', '#fb923c');
  const yellow = cssVar('--rl-yellow', '#facc15');
  const red = cssVar('--rl-red', '#f87171');
  return {
    text: cssVar('--rl-text', '#e6ebf5'),
    muted: cssVar('--rl-muted', '#9aa8c4'),
    grid: cssVar('--rl-border', '#1b2740'),
    series: [blue, cyan, purple, green, orange, yellow, red, '#94a3b8'],
    green,
    red,
    orange,
    yellow,
    blue,
    cyan,
    purple,
  };
}

/** Base options shared by every chart (axes coloured from the theme). */
export function baseChartOptions(p: ChartPalette, opts: { stacked?: boolean; percent?: boolean; legend?: boolean; horizontal?: boolean } = {}) {
  const tickColor = p.muted;
  return {
    maintainAspectRatio: false,
    responsive: true,
    indexAxis: opts.horizontal ? ('y' as const) : ('x' as const),
    animation: { duration: 250 },
    plugins: {
      legend: { display: opts.legend ?? true, labels: { color: p.text, boxWidth: 12, font: { size: 11 } } },
      tooltip: { mode: 'index' as const, intersect: false },
    },
    scales: {
      x: {
        stacked: opts.stacked ?? false,
        ticks: { color: tickColor, font: { size: 11 } },
        grid: { color: p.grid },
      },
      y: {
        stacked: opts.stacked ?? false,
        beginAtZero: true,
        ticks: {
          color: tickColor,
          font: { size: 11 },
          ...(opts.percent ? { callback: (v: string | number) => `${v}%` } : {}),
        },
        grid: { color: p.grid },
      },
    },
  };
}
