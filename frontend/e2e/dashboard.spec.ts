import { expect, test } from '@playwright/test';

test('dashboard loads with KPIs or an empty state and the demo shortcuts', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/dashboard/);
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  const kpis = page.getByTestId('dashboard-kpis');
  const empty = page.getByText(/no experiments yet/i);
  await expect(kpis.or(empty).first()).toBeVisible();
  await expect(page.getByTestId('demo-card').first()).toBeVisible();
});
