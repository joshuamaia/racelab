import { expect, test } from '@playwright/test';
import { LOST_UPDATE_PRESET, waitForTerminalStatus } from './helpers';

test('Demo 1 — lost update shows INCONSISTENCY and an INVÁLIDA state row', async ({ page }) => {
  await page.goto(`/lab/concurrency?preset=${LOST_UPDATE_PRESET}&strategy=NONE&autorun=1`);
  await waitForTerminalStatus(page);
  await expect(page.getByText('INCONSISTENCY').first()).toBeVisible();
  await expect(page.getByTestId('resource-state').getByText('INVÁLIDA').first()).toBeVisible();
});

test('optimistic locking produces a version conflict', async ({ page }) => {
  await page.goto(`/lab/concurrency?preset=${LOST_UPDATE_PRESET}&strategy=OPTIMISTIC_LOCKING&autorun=1`);
  await waitForTerminalStatus(page);
  await expect(page.locator('.tl-card').filter({ hasText: /Optimistic lock conflict/i }).first()).toBeVisible();
  await expect(page.getByText('CONSISTENT').first()).toBeVisible();
});

test('step-by-step: next step releases a checkpoint', async ({ page }) => {
  await page.goto(`/lab/concurrency?preset=${LOST_UPDATE_PRESET}`);
  await page.getByTestId('run-step-btn').locator('button').click();
  const next = page.getByTestId('next-step-btn').locator('button');
  await expect(next).toBeEnabled({ timeout: 30_000 });
  await next.click();
  await expect(page.locator('.tl-card').filter({ hasText: /Checkpoint released/i }).first()).toBeVisible();
  // finish quickly
  await page.getByRole('button', { name: /Resume \(Continuar\)/ }).first().click();
  await waitForTerminalStatus(page);
});
