import { test, expect, type Page } from '@playwright/test';

async function login(page: Page, email = 'engineer@fleettruth.demo') {
  await page.goto('/');
  await page.getByLabel('Email address').fill(email);
  await page.getByRole('button', { name: 'Open workspace', exact: true }).click();
  await expect(page.locator('.metric-value').first()).toHaveText('100,000', { timeout: 30000 });
}

test('both avatars open the authenticated profile with keyboard-accessible sections', async ({ page }) => {
  await login(page);
  const response = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/auth/me');
  await page.getByRole('button', { name: 'Open user profile', exact: true }).click();
  const me = await (await response).json();
  const drawer = page.getByRole('dialog', { name: 'User profile', exact: true });
  await expect(drawer).toBeVisible();
  await expect(drawer.locator('.account-details')).toContainText(me.email);
  await expect(drawer.locator('.account-details')).toContainText(me.tenantId);
  await expect(drawer.getByRole('button', { name: 'Close user profile' })).toBeFocused();
  await drawer.getByRole('tab', { name: 'Account', exact: true }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(drawer.getByRole('tab', { name: 'Preferences' })).toHaveAttribute('aria-selected', 'true');
  await page.keyboard.press('End');
  await expect(drawer.getByRole('tab', { name: 'Access', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await expect(
    drawer.locator('.permission-list li').filter({ hasText: 'Approve mappings and replay' }),
  ).toContainText('Allowed');
  await expect(
    drawer.locator('.permission-list li').filter({ hasText: 'Erase vehicle evidence' }),
  ).toContainText('Restricted');
  await page.keyboard.press('Escape');
  await expect(drawer).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Open user profile', exact: true })).toBeFocused();
  await page.getByRole('button', { name: 'Open account profile', exact: true }).click();
  await expect(drawer.locator('.account-details')).toContainText('Alex Morgan');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: 'Open account profile', exact: true })).toBeFocused();
});

test('day and night choices persist, while System follows the OS and synchronizes between tabs', async ({
  page,
  context,
}) => {
  await page.emulateMedia({ colorScheme: 'light' });
  await login(page);
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await page.getByRole('button', { name: 'Switch to night mode' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.getByRole('button', { name: 'Open user profile', exact: true }).click();
  await page.getByRole('tab', { name: 'Preferences' }).click();
  await expect(page.getByRole('radio', { name: 'Night', exact: true })).toBeChecked();
  await page.getByRole('radio', { name: 'Day', exact: true }).check();
  await page.emulateMedia({ colorScheme: 'dark' });
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await page.getByRole('radio', { name: 'System', exact: true }).check();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.emulateMedia({ colorScheme: 'light' });
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await page.keyboard.press('Escape');
  const other = await context.newPage();
  await other.goto('/');
  await page.getByRole('button', { name: 'Switch to night mode' }).click();
  await expect(other.locator('html')).toHaveAttribute('data-theme', 'dark');
  await other.close();
});

for (const account of [
  { email: 'viewer@fleettruth.demo', initials: 'FR', erasure: 'Restricted', raw: 'Restricted' },
  { email: 'admin@fleettruth.demo', initials: 'AM', erasure: 'Allowed', raw: 'Allowed' },
]) {
  test(`profile reflects ${account.email} permissions and signs out without exposing credentials`, async ({
    page,
  }) => {
    await login(page, account.email);
    await expect(page.getByRole('button', { name: 'Open user profile', exact: true })).toHaveText(
      account.initials,
    );
    await page.getByRole('button', { name: 'Open user profile', exact: true }).click();
    await page.getByRole('tab', { name: 'Access', exact: true }).click();
    await expect(
      page.locator('.permission-list li').filter({ hasText: 'Inspect raw telemetry' }),
    ).toContainText(account.raw);
    await expect(
      page.locator('.permission-list li').filter({ hasText: 'Erase vehicle evidence' }),
    ).toContainText(account.erasure);
    await expect(page.getByRole('dialog')).not.toContainText('FleetTruth2026!');
    await page.getByRole('button', { name: 'Sign out of account', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Open workspace', exact: true })).toBeVisible();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    expect(await page.evaluate(() => sessionStorage.getItem('fleettruth-session'))).toBeNull();
  });
}

test('profile read errors are recoverable', async ({ page }) => {
  await login(page);
  await page.route('**/api/auth/me', (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ message: 'Temporarily unavailable' }),
    }),
  );
  await page.getByRole('button', { name: 'Open user profile', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Account unavailable' })).toBeVisible();
  await page.unroute('**/api/auth/me');
  await page.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(page.locator('.account-details')).toContainText('engineer@fleettruth.demo');
});

for (const theme of ['light', 'dark'] as const) {
  for (const width of [390, 768, 1242]) {
    test(`${theme} workspace and profile fit ${width}px without clipped text`, async ({ page }) => {
      test.setTimeout(180000);
      await page.setViewportSize({ width, height: 900 });
      await page.addInitScript((theme) => localStorage.setItem('fleettruth-theme', theme), theme);
      const errors: string[] = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await login(page);
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
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
        await expect(page.locator('main .loading')).toHaveCount(0, { timeout: 30000 });
        await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
        if (view === 'overview') {
          const clipped = await page
            .locator('.metric-value')
            .evaluateAll((elements) => elements.some((element) => element.scrollWidth > element.clientWidth));
          expect(clipped).toBe(false);
          await page.screenshot({
            path: `test-results/screenshots/overview-${theme}-${width}.png`,
            fullPage: true,
            animations: 'disabled',
          });
        }
      }
      await page.getByRole('button', { name: 'Open user profile', exact: true }).click();
      const profile = page.getByRole('dialog', { name: 'User profile', exact: true });
      await expect(profile.locator('.account-details')).toContainText('engineer@fleettruth.demo');
      await page.screenshot({
        path: `test-results/screenshots/profile-${theme}-${width}.png`,
        animations: 'disabled',
      });
      await page.getByRole('tab', { name: 'Preferences' }).click();
      await expect(
        page.getByRole('radio', { name: theme === 'dark' ? 'Night' : 'Day', exact: true }),
      ).toBeChecked();
      await expect(profile).toHaveJSProperty(
        'scrollWidth',
        await profile.evaluate((element) => element.clientWidth),
      );
      await page.screenshot({
        path: `test-results/screenshots/preferences-${theme}-${width}.png`,
        animations: 'disabled',
      });
      const colors = await page.evaluate(() => {
        const css = getComputedStyle(document.documentElement);
        return ['--text-primary', '--text-secondary', '--text-muted', '--text-faint', '--bg-card'].map(
          (name) => css.getPropertyValue(name).trim(),
        );
      });
      const luminance = (hex: string) => {
        const channels = [1, 3, 5]
          .map((index) => parseInt(hex.slice(index, index + 2), 16) / 255)
          .map((value) => (value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4));
        return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
      };
      const background = luminance(colors[4]);
      for (const text of colors.slice(0, 4)) {
        const foreground = luminance(text);
        expect(
          (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05),
        ).toBeGreaterThanOrEqual(4.5);
      }
      expect(errors).toEqual([]);
    });
  }
}
