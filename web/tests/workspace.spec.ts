import { test, expect } from '@playwright/test';
test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Open workspace' }).click();
  await expect(page.getByRole('heading', { name: 'Fleet overview', exact: true })).toBeVisible();
});
test('overview is connected, responsive, and free of runtime errors', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await expect(page.locator('.metric-value').first()).toHaveText('100,000');
  await expect(page.locator('.oem-row')).toHaveCount(4);
  await expect(page.locator('.recharts-surface').first()).toBeVisible();
  await page.screenshot({ path: 'test-results/screenshots/overview-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 390);
  await page.screenshot({ path: 'test-results/screenshots/overview-mobile.png', fullPage: true });
  await page.getByRole('button', { name: 'Open navigation' }).click();
  await page.getByRole('button', { name: 'OEM integrations', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'OEM integrations', exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});
test('engineer validates a schema, approves it, and recovers retained events', async ({ page }) => {
  test.setTimeout(120000);
  await page.getByRole('button', { name: 'Mapping studio', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Mapping studio', exact: true })).toBeVisible();
  await expect(page.locator('.mapping-editor-header h2')).toBeVisible();
  if ((await page.getByRole('button', { name: 'Approve mapping', exact: true }).count()) === 0) {
    const quarantined = await page.evaluate(async () => {
      const session = JSON.parse(sessionStorage.getItem('fleettruth-session') || 'null');
      const response = await fetch('/api/overview', { headers: { Authorization: `Bearer ${session.token}` } });
      if (!response.ok) throw new Error('Cannot inspect recovery state');
      return (await response.json()).quarantined;
    });
    // Resume an interrupted recovery only when retained events actually remain.
    if (quarantined > 0) {
      await page.getByRole('button', { name: 'Replay events', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Recovery center' })).toBeVisible({ timeout: 30000 });
      await expect(page.locator('.data-table .badge').first()).toHaveText(/completed/, { timeout: 30000 });
    }
    await page.getByRole('button', { name: 'OEM integrations', exact: true }).click();
    await page.getByRole('button', { name: 'Inject schema change' }).click();
  }
  await page.getByRole('button', { name: 'Run validation' }).click();
  await expect(page.getByText('All checks passed')).toBeVisible({ timeout: 30000 });
  await page.getByRole('button', { name: 'Payload comparison' }).click();
  await expect(page.locator('.payload-comparison pre').first()).toContainText('soc_fraction');
  await page.screenshot({ path: 'test-results/screenshots/mapping-studio.png', fullPage: true });
  await page.getByRole('button', { name: 'Approve mapping', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm approval', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Replay events', exact: true })).toBeVisible({
    timeout: 30000,
  });
  await page.getByRole('button', { name: 'Replay events', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Recovery center' })).toBeVisible({ timeout: 30000 });
  await expect(page.locator('.data-table .badge').first()).toHaveText(/completed/, { timeout: 30000 });
  await page.screenshot({ path: 'test-results/screenshots/recovery.png', fullPage: true });
  await page.getByRole('button', { name: 'Audit trail', exact: true }).click();
  await expect(page.getByText('mapping approved', { exact: true }).first()).toBeVisible();
});
test('vehicle search and evidence drawer read actual persisted records', async ({ page }) => {
  await page.clock.install();
  await page.getByRole('button', { name: 'Vehicles', exact: true }).click();
  await expect(page.locator('.data-table tbody tr')).toHaveCount(25);
  const first = await page.locator('.data-table .vehicle-link').first().innerText();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.table-footer')).toContainText('Page 2');
  await expect(page.locator('.data-table .vehicle-link').first()).not.toHaveText(first);
  await page.clock.fastForward(500);
  await expect(page.locator('.table-footer')).toContainText('Page 2');
  await page.getByRole('button', { name: 'Previous', exact: true }).click();
  await page.locator('.data-table .vehicle-link').first().click();
  await expect(page.getByRole('dialog', { name: 'Vehicle details' })).toBeVisible();
  await expect(page.getByText('Event timeline', { exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/screenshots/vehicle-detail.png', fullPage: true });
});
test('assistant retrieves workspace evidence and records its tools', async ({ page }) => {
  await page.getByRole('button', { name: 'Fleet assistant', exact: true }).click();
  await page.getByRole('button', { name: 'What needs attention across the fleet?' }).click();
  await expect(page.getByText('2 audited tool calls')).toBeVisible();
  await expect(page.locator('.agent-message')).toContainText('100000');
  await page.screenshot({ path: 'test-results/screenshots/assistant.png', fullPage: true });
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Close assistant', exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: 'Fleet assistant', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Fleet assistant', exact: true })).toBeFocused();
});
test('reviewer has read-only controls and cannot see precise evidence', async ({ page }) => {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.getByRole('button', { name: 'Reviewer', exact: true }).click();
  await page.getByRole('button', { name: 'Open workspace' }).click();
  await page.getByRole('button', { name: 'Mapping studio', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Run validation' })).toBeDisabled();
  await page.getByRole('button', { name: 'Vehicles', exact: true }).click();
  await page.locator('.data-table .vehicle-link').first().click();
  await expect(page.getByText('Location (masked)', { exact: true })).toBeVisible();
  await expect(page.getByText('Event timeline', { exact: true })).toHaveCount(0);
});
