/**
 * c168293d: `errors.rateLimited` erschien bisher nur in `onError`-Toasts. Seiten, die
 * Queries ohne Toast rendern, zeigten im Backoff-Fenster nur leere Zustände. Der Hinweis
 * gehört an eine zentrale, unaufdringliche Stelle — solange eine Sperre aktiv ist.
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

/** Löst einen 429 aus, damit eine Sperre aktiv ist. */
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

  it('zeigt nichts, solange keine Sperre aktiv ist', () => {
    const view = render(<RateLimitBanner />);
    expect(view.container.textContent).toBe('');
  });

  it('zeigt den Hinweis mit der Uhrzeit, bis zu der pausiert wird', async () => {
    await triggerBackoff();
    const view = render(<RateLimitBanner />);
    await advance(0);

    const banner = view.getByRole('status');
    expect(banner.textContent).toContain('Zu viele Anfragen');
    expect(banner.textContent).toContain(new Date(NOW + 5_000).toLocaleTimeString());
  });

  it('nutzt Warn-Token statt fester Tailwind-Farben', async () => {
    await triggerBackoff();
    const view = render(<RateLimitBanner />);
    await advance(0);

    const banner = view.getByRole('status');
    expect(banner.className).toContain('bg-warning-subtle');
    expect(banner.className).toContain('text-warning');
  });

  it('verschwindet, sobald die Sperre abgelaufen ist', async () => {
    await triggerBackoff();
    const view = render(<RateLimitBanner />);
    await advance(0);
    expect(view.queryByRole('status')).not.toBeNull();

    await advance(5_500);
    expect(view.queryByRole('status')).toBeNull();
  });

  it('erscheint auch, wenn die Sperre erst nach dem Mount beginnt', async () => {
    const view = render(<RateLimitBanner />);
    expect(view.queryByRole('status')).toBeNull();

    await triggerBackoff();
    await advance(1_100);
    expect(view.queryByRole('status')).not.toBeNull();
  });

  it('übersetzt den Hinweis ins Englische', async () => {
    useI18nStore.setState({ locale: 'en' });
    await triggerBackoff();
    const view = render(<RateLimitBanner />);
    await advance(0);

    expect(view.getByRole('status').textContent).toContain('Too many requests');
  });
});
