import { Page, expect } from '@playwright/test';

/** Demo 1 preset id (see docs/API-CONTRACT.md catalog example). */
export const LOST_UPDATE_PRESET = 'lost-update';

export async function waitForTerminalStatus(page: Page): Promise<void> {
  await expect(page.getByLabel(/^Status: (Completed|Failed|Cancelled)$/).first()).toBeVisible({ timeout: 90_000 });
}
