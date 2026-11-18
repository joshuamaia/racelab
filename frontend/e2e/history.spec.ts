import { expect, test } from '@playwright/test';
import { LOST_UPDATE_PRESET, waitForTerminalStatus } from './helpers';

test('history rerun opens the new experiment', async ({ page }) => {
  // make sure there is at least one experiment
  await page.goto(`/lab/concurrency?preset=${LOST_UPDATE_PRESET}&autorun=1`);
  await waitForTerminalStatus(page);

  await page.goto('/history');
  await expect(page.getByTestId('history-table')).toBeVisible();
  const rerun = page.getByTestId('history-rerun').first();
  await expect(rerun).toBeVisible();
  await rerun.locator('button').or(rerun).first().click();
  await expect(page).toHaveURL(/\/experiments\/[^/]+$/);
  await waitForTerminalStatus(page);
});
