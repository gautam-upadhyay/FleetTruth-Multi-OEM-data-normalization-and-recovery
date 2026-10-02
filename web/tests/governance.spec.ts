import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Open workspace' }).click();
  await expect(page.getByRole('heading', { name: 'Fleet overview', exact: true })).toBeVisible();
});

test('drift scores come from the API and remain advisory', async ({ page }) => {
  const responsePromise = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/ml/drift' && response.request().method() === 'GET',
  );
  await page.getByRole('button', { name: 'OEM integrations', exact: true }).click();
  const response = await responsePromise;
  expect(response.status()).toBe(200);
  const result = await response.json();
  expect(result.advisory).toBe(true);
  expect(result.streams).toHaveLength(4);
  await expect(page.getByRole('heading', { name: 'Drift intelligence' })).toBeVisible();
  await expect(page.locator('.drift-panel tbody tr')).toHaveCount(4);
  await expect(page.locator('.model-disclosure')).toContainText('not real-OEM validation');
  await page.screenshot({ path: 'test-results/screenshots/drift-intelligence.png', fullPage: true });
});

test('administrator erasure requires exact VIN confirmation', async ({ page }) => {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.getByLabel('Email address').fill('admin@fleettruth.demo');
  await page.getByRole('button', { name: 'Open workspace' }).click();
  await page.getByRole('button', { name: 'Audit trail', exact: true }).click();
  await page.getByRole('button', { name: 'Erase vehicle evidence', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByLabel('Vehicle VIN', { exact: true }).fill('MFT00000500000001');
  await page.getByLabel('Confirm VIN', { exact: true }).fill('MFT00000500000002');
  await expect(page.getByRole('button', { name: 'Erase local evidence' })).toBeDisabled();
  await expect(page.getByRole('dialog')).toContainText('separate purge verification');
  await page.screenshot({ path: 'test-results/screenshots/privacy-confirmation.png' });
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
});

test('analytics renders persisted daily data and exports the same response', async ({ page }) => {
  const responsePromise = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/analytics' && response.request().method() === 'GET',
  );
  await page.getByRole('button', { name: 'Analytics', exact: true }).click();
  const response = await responsePromise;
  expect(response.status()).toBe(200);
  const data = await response.json();
  expect(data.daily.length).toBeGreaterThan(0);
  expect(data.oems).toHaveLength(4);
  await expect(page.getByText('Daily quality report', { exact: true })).toBeVisible();
  await expect(page.locator('table tbody tr')).toHaveCount(data.daily.length);
  await expect(page.locator('table tbody tr').first()).toContainText(data.daily[0].day);
  await expect(page.getByText('Analytics unavailable', { exact: true })).toHaveCount(0);
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export report', exact: true }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('fleettruth-analytics.json');
  const stream = await download.createReadStream();
  expect(stream).not.toBeNull();
  const chunks: Buffer[] = [];
  for await (const chunk of stream!) chunks.push(Buffer.from(chunk));
  expect(JSON.parse(Buffer.concat(chunks).toString('utf8'))).toEqual(data);
  await page.screenshot({ path: 'test-results/screenshots/analytics-desktop.png', fullPage: true });
});

test('all workspace views fit mobile and tablet widths', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  for (const width of [390, 768]) {
    await page.setViewportSize({ width, height: 900 });
    for (const view of [
      'overview',
      'vehicles',
      'alerts',
      'integrations',
      'mappings',
      'recovery',
      'analytics',
      'audit',
    ]) {
      await page.goto('/#/' + view);
      await expect(page.locator('main h1')).toBeVisible();
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
    }
  }
  expect(errors).toEqual([]);
});
