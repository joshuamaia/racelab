import { expect, test } from '@playwright/test';

test('comparator runs two strategies and shows the disclaimer and table', async ({ page }) => {
  await page.goto('/comparator?scenarioId=bank-withdrawal&strategies=NONE,ATOMIC_UPDATE');
  const run = page.getByTestId('run-comparison').locator('button');
  await expect(run).toBeEnabled();
  await run.click();
  const result = page.getByTestId('comparison-result');
  await expect(result).toBeVisible();
  await expect(result.getByLabel('Status: Completed').first()).toBeVisible({ timeout: 110_000 });
  await expect(result.getByText(/Read before comparing/)).toBeVisible();
});
