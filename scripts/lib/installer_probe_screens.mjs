// Screenshots for scripts/installer-probe.sh: the first visit (login) and the page after logging in
// with the freshly generated API key. Headless, its own browser — never the shared Playwright MCP
// browser that workflow runs use.
//
//   PLAYWRIGHT_MODULE=<…/node_modules/playwright/index.mjs> PROBE_API_KEY=<key> \
//     node installer_probe_screens.mjs <frontend-url> <out-dir>
//
// Prints one line per result ("login ok", "dashboard ok", "console-errors N") and exits 1 when
// the login field never shows up or the login does not lead away from the login screen.
const [url, outDir] = process.argv.slice(2);
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE);
const key = process.env.PROBE_API_KEY ?? '';

const browser = await chromium.launch();
let failed = false;
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

  await page.goto(url, { waitUntil: 'networkidle', timeout: 60_000 });
  // ApiKeyGuard renders the key field as the only password input; the dashboard has none.
  const field = page.locator('input[type="password"]');
  await field.waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForTimeout(500); // let the fade-in finish, otherwise the capture is half transparent
  await page.screenshot({ path: `${outDir}/login.png` });
  console.log('login ok');

  await field.fill(key);
  await field.press('Enter');
  try {
    await field.waitFor({ state: 'detached', timeout: 20_000 });
    await page.waitForLoadState('networkidle', { timeout: 30_000 });
    await page.waitForTimeout(1500);
    await page.screenshot({ path: `${outDir}/dashboard.png` });
    console.log('dashboard ok');
  } catch {
    await page.screenshot({ path: `${outDir}/dashboard.png` });
    console.log('dashboard failed: login screen still visible after entering the key');
    failed = true;
  }
  console.log(`console-errors ${errors.length}`);
} catch (err) {
  console.log(`login failed: ${String(err).split('\n')[0]}`);
  failed = true;
} finally {
  await browser.close();
}
process.exit(failed ? 1 : 0);
