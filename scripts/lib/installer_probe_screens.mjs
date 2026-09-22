// Screenshots for scripts/installer-probe.sh: the first visit (login) and the page after logging in
// with the freshly generated API key. Headless, its own browser — never the shared Playwright MCP
// browser that workflow runs use.
//
//   PLAYWRIGHT_MODULE=<…/node_modules/playwright/index.mjs> PROBE_API_KEY=<key> \
//     node installer_probe_screens.mjs <frontend-url> <out-dir>
//
// Prints one line per result ("login ok", "dashboard ok", "console-errors N") and exits 1 when
// the login field never shows up or the login does not lead away from the login screen.
// Exits 3 with "browser missing: <path>" when the Chromium that this Playwright version expects is
// not downloaded — typical right after a Playwright update (65164c57). Fix: `npm run pw:install` in
// ai-automation-frontend; this script never downloads anything itself.
const BROWSER_MISSING_EXIT = 3;
const [url, outDir] = process.argv.slice(2);
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE);
const key = process.env.PROBE_API_KEY ?? '';

// Detected from launch() itself, not by checking executablePath() up front: headless runs use the
// separate headless shell, so a missing full Chromium alone would be a false alarm.
let browser;
try {
  browser = await chromium.launch();
} catch (err) {
  const text = String(err);
  if (/Executable doesn't exist|playwright install/i.test(text)) {
    const path = (text.match(/Executable doesn't exist at (\S+)/) || [])[1] || 'unknown path';
    console.log(`browser missing: ${path} — run \`npm run pw:install\` in ai-automation-frontend`);
    process.exit(BROWSER_MISSING_EXIT);
  }
  console.log(`login failed: browser did not start: ${text.split('\n')[0]}`);
  process.exit(1);
}
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
  // The texts themselves: a count alone does not tell a 401 before the login from a real error
  for (const e of errors.slice(0, 5)) console.log(`console-error ${e.replace(/\s+/g, ' ').slice(0, 200)}`);
} catch (err) {
  console.log(`login failed: ${String(err).split('\n')[0]}`);
  failed = true;
} finally {
  await browser.close();
}
process.exit(failed ? 1 : 0);
