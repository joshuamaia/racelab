/**
 * Captures the README screenshots from the running stack (http://localhost:4200).
 * Usage: npx tsx e2e/screenshots.ts   (or: node --experimental-strip-types e2e/screenshots.ts)
 */
import { chromium } from '@playwright/test';

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:4200';
const OUT = process.env.SCREENSHOT_DIR ?? '../docs/screenshots';

async function api(path: string, body?: unknown): Promise<any> {
  const r = await fetch(`${BASE}/api${path}`, {
    method: body ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  return r.json();
}

async function runPreset(presetId: string): Promise<string> {
  const e = await api('/experiments', { presetId, autoStart: true });
  for (let i = 0; i < 120; i++) {
    const s = await api(`/experiments/${e.id}`);
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(s.status)) break;
    await new Promise((r) => setTimeout(r, 250));
  }
  return e.id;
}

(async () => {
  const lost = await runPreset('lost-update');
  const deadlock = await runPreset('deadlock');
  await runPreset('lost-update-optimistic');
  await runPreset('write-skew');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 });
  const shots: [string, string][] = [
    ['dashboard', '/dashboard'],
    ['experiment-lost-update', `/experiments/${lost}`],
    ['experiment-deadlock', `/experiments/${deadlock}`],
    ['concurrency-lab', '/lab/concurrency'],
    ['isolation-lab', '/lab/isolation'],
    ['comparator', '/comparator'],
    ['code-explorer', '/code'],
    ['inspector', '/inspector'],
  ];
  for (const [name, path] of shots) {
    await page.goto(BASE + path, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1500);
    await page.screenshot({ path: `${OUT}/${name}.png` });
    console.log('captured', name);
  }
  await browser.close();
})();
