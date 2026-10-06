/**
 * c168293d: `errors.rateLimited` erschien bisher nur in `onError`-Toasts. Seiten, die
 * queries without a toast showed only empty states in the backoff window. The hint
 * belongs in one central, unobtrusive place - as long as a lockout is active.
 */
import { render, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RateLimitBanner } from '../RateLimitBanner';
import { api, resetRateLimitBackoff } from '../../../api/client';
import { useI18nStore } from '../../../i18n';

const NOW = Date.parse('2026-09-14T12:00:00.000Z');

function rateLimited(): Response {
  return new Response(JSON.stringify({ error: 'Rate limit exceeded', timestamp: 'x' }), {
    status: 429,
    headers: { 'Content-Type': 'application/json', 'Retry-After': '5' },
  });
}

async function advance(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
}

/** Triggers a 429 so that a lockout is active. */
async function triggerBackoff() {
  await act(async () => { await api.get('/system/capabilities').catch(() => undefined); });
}

describe('RateLimitBanner (c168293d)', () => {
  beforeEach(() => {
    resetRateLimitBackoff();
    useI18nStore.setState({ locale: 'de' });
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    vi.stubGlobal('fetch', vi.fn(async () => rateLimited()));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    resetRateLimitBackoff();
    useI18nStore.setState({ locale: 'en' });
  });

  it('shows nothing as long as no lockout is active', () => {
    const view = render(<RateLimitBanner />);
    expect(view.container.textContent).toBe('');
  });

  it('shows the hint with the time until which requests are paused', async () => {
    await triggerBackoff();
    const view = render(<RateLimitBanner />);
    await advance(0);

    const banner = view.getByRole('status');
    expect(banner.textContent).toContain('Zu viele Anfragen');
    // App language, not browser locale.
    expect(banner.textContent).toContain(new Date(NOW + 5_000).toLocaleTimeString('de-DE'));
  });

  it('uses the warning token instead of fixed Tailwind colours', async () => {
    await triggerBackoff();
    const view = render(<RateLimitBanner />);
    await advance(0);

    const banner = view.getByRole('status');
    expect(banner.className).toContain('bg-warning-subtle');
    expect(banner.className).toContain('text-warning');
  });

  it('disappears as soon as the lock has expired', async () => {
    await triggerBackoff();
    const view = render(<RateLimitBanner />);
    await advance(0);
    expect(view.queryByRole('status')).not.toBeNull();

    await advance(5_500);
    expect(view.queryByRole('status')).toBeNull();
  });

  it('also appears when the lockout begins only after the mount', async () => {
    const view = render(<RateLimitBanner />);
    expect(view.queryByRole('status')).toBeNull();

    await triggerBackoff();
    await advance(1_100);
    expect(view.queryByRole('status')).not.toBeNull();
  });

  it('translates the hint into English', async () => {
    useI18nStore.setState({ locale: 'en' });
    await triggerBackoff();
    const view = render(<RateLimitBanner />);
    await advance(0);

    const banner = view.getByRole('status');
    expect(banner.textContent).toContain('Too many requests');
    // 24 h in English too (571524a5)
    expect(banner.textContent).toContain(new Date(NOW + 5_000).toLocaleTimeString('en-US', { hourCycle: 'h23' }));
    expect(banner.textContent).not.toMatch(/\d\s[AP]M/);
  });

  it('disappears without its own timer - the client reports the expiry', async () => {
    await triggerBackoff();
    const view = render(<RateLimitBanner />);
    await advance(0);
    expect(view.queryByRole('status')).not.toBeNull();

    // Exactly at the deadline: without the expiry edge from the client the banner would stay.
    await advance(5_000);
    expect(view.queryByRole('status')).toBeNull();
  });
});
