import { chromium, expect as baseExpect } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../../', import.meta.url));
const expect = baseExpect.configure({ timeout: 30000 });
const output = path.join(root, 'artifacts', 'demo');
await mkdir(output, { recursive: true });
const browser = await chromium.launch({
  channel: process.platform === 'win32' ? 'msedge' : undefined,
  headless: true,
});
const context = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  recordVideo: { dir: path.join(output, 'raw'), size: { width: 1440, height: 1000 } },
});
let page;
let video;
const started = Date.now();
const chapters = [];
async function hold(title, seconds = 6) {
  chapters.push({ title, startsAtSeconds: Number(((Date.now() - started) / 1000).toFixed(1)) });
  console.log(title);
  await page.waitForTimeout(seconds * 1000);
}
async function navigate(name) {
  await page.locator('nav').getByText(name, { exact: true }).click();
  await page.locator('main h1').scrollIntoViewIfNeeded();
}
try {
  page = await context.newPage();
  page.setDefaultTimeout(30000);
  video = page.video();
  await page.goto(process.env.APP_URL || 'http://127.0.0.1:5173');
  await page.getByRole('button', { name: 'Open workspace' }).click();
  await expect(page.locator('.metric-value').first()).toHaveText('100,000');
  await hold('A synthetic 100K-vehicle registry; live subset, not 100K events/sec', 7);

  await page.getByRole('button', { name: 'Open user profile', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'User profile', exact: true })).toBeVisible();
  await hold('Authenticated user profile with tenant and role details', 5);
  await page.getByRole('tab', { name: 'Preferences' }).click();
  await page.getByRole('radio', { name: 'Night', exact: true }).check();
  await hold('Night mode and persisted workspace preferences', 4);
  await page.keyboard.press('Escape');
  await hold('Fleet overview in night mode', 4);
  await page.getByRole('button', { name: 'Switch to day mode' }).click();
  await hold('Fleet overview in day mode', 4);

  await navigate('OEM integrations');
  await expect(page.getByRole('button', { name: 'Inject schema change' })).toBeEnabled();
  await hold('Four manufacturers, one canonical telemetry contract', 5);
  await page.getByRole('button', { name: 'Inject schema change' }).click();
  await expect(page.getByRole('heading', { name: 'Mapping studio', exact: true })).toBeVisible();
  await navigate('OEM integrations');
  await expect(page.getByRole('button', { name: 'Inject schema change' })).toBeDisabled();
  await hold('Schema drift is quarantined; invalid data is not silently trusted', 7);

  await navigate('Mapping studio');
  await page.getByRole('button', { name: 'Run validation' }).click();
  await expect(page.getByText('All checks passed')).toBeVisible();
  await hold('Validate the versioned conversion against retained samples', 7);
  await page.getByRole('button', { name: 'Payload comparison' }).click();
  await expect(page.locator('.payload-comparison pre').first()).toContainText('soc_fraction');
  await hold('Fractional battery source becomes an explicit canonical percentage', 9);
  await page.getByRole('button', { name: 'Approve mapping', exact: true }).click();
  await hold('Human approval is required; the assistant cannot authorize a mapping', 4);
  await page.getByRole('button', { name: 'Confirm approval', exact: true }).click();
  await page.getByRole('button', { name: 'Replay events', exact: true }).click();
  await expect(page.locator('.data-table .badge').first()).toHaveText(/completed/, { timeout: 30000 });
  await hold('Durable recovery reprocesses the original quarantined evidence', 7);

  await navigate('Alerts');
  await expect(page.locator('.data-table tbody tr').first()).toBeVisible();
  await hold('Operational alerts use normalized battery and speed evidence', 7);
  await navigate('Vehicles');
  await page.locator('.data-table .vehicle-link').first().click();
  await expect(page.getByRole('dialog', { name: 'Vehicle details' })).toBeVisible();
  await expect(page.getByText('Event timeline', { exact: true })).toBeVisible();
  await hold('Original and normalized vehicle evidence stays inspectable', 7);
  await page.keyboard.press('Escape');

  await page.getByRole('button', { name: 'Fleet assistant', exact: true }).click();
  await page.getByRole('button', { name: 'What needs attention across the fleet?' }).click();
  await expect(page.getByText('2 audited tool calls')).toBeVisible();
  await hold('Read-only assistant retrieves tenant-scoped evidence', 7);
  await page.keyboard.press('Escape');
  await navigate('Audit trail');
  await expect(page.getByText('mapping approved', { exact: true }).first()).toBeVisible();
  await hold('Approvals, replay and tool use are traceable', 7);

  await navigate('Analytics');
  await expect(page.getByText('Daily quality report', { exact: true })).toBeVisible();
  await hold('Historical quality uses transactionally maintained ledger summaries', 7);
  await navigate('Overview');
  await expect(page.getByText('All OEM contracts are healthy', { exact: true })).toBeVisible();
  await hold('Recovered contracts; local evidence is separate from production-scale claims', 7);

  const durationSeconds = (Date.now() - started) / 1000;
  if (durationSeconds >= 300) throw new Error('Recording exceeded the five-minute submission limit');
  await context.close();
  await video.saveAs(path.join(output, 'FleetTruth-demo.webm'));
  await writeFile(
    path.join(output, 'chapters.json'),
    JSON.stringify(
      {
        recordedAt: new Date().toISOString(),
        durationSeconds,
        audio: false,
        scope: 'Unedited local synthetic workflow capture; not performance, cloud or production proof',
        chapters,
      },
      null,
      2,
    ) + '\n',
  );
  console.log(`Saved ${path.join(output, 'FleetTruth-demo.webm')}`);
} finally {
  await context.close();
  await browser.close();
}
