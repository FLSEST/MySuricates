/** Unit tests für den 429-Backoff des API-Clients (d89b2cc1). */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  api,
  apiErrorMessage,
  parseRetryAfter,
  rateLimitRemainingSeconds,
  resetRateLimitBackoff,
} from '../client';

const NOW = Date.parse('2026-09-14T12:00:00.000Z');

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

describe('parseRetryAfter', () => {
  it('liest Sekunden als Delta', () => {
    expect(parseRetryAfter('5', NOW)).toBe(5);
    expect(parseRetryAfter(' 42 ', NOW)).toBe(42);
  });

  it('liest ein HTTP-Datum als Abstand zu jetzt', () => {
    expect(parseRetryAfter('Mon, 14 Sep 2026 12:00:07 GMT', NOW)).toBe(7);
  });

  it('gibt 0 für ein Datum in der Vergangenheit', () => {
    expect(parseRetryAfter('Mon, 14 Sep 2026 11:59:50 GMT', NOW)).toBe(0);
  });

  it('nimmt 10 s, wenn der Header fehlt oder unlesbar ist', () => {
    expect(parseRetryAfter(null, NOW)).toBe(10);
    expect(parseRetryAfter(undefined, NOW)).toBe(10);
    expect(parseRetryAfter('', NOW)).toBe(10);
    expect(parseRetryAfter('bald', NOW)).toBe(10);
  });
});

describe('429-Backoff im API-Client', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    resetRateLimitBackoff();
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    fetchMock = vi.fn(async () => jsonResponse(429, { error: 'Rate limit exceeded', timestamp: 'x' }, { 'Retry-After': '5' }));
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    resetRateLimitBackoff();
  });

  it('sperrt den Pfad nach einem 429 und weist Folgeaufrufe ohne Netzanfrage ab', async () => {
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429, code: 'RATE_LIMITED' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(rateLimitRemainingSeconds('/system/capabilities')).toBe(5);

    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429, code: 'RATE_LIMITED' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('sperrt pro Pfad ohne Query — Query-Varianten teilen sich die Sperre', async () => {
    await expect(api.get('/assistant/suggestions?page=workflows')).rejects.toMatchObject({ status: 429 });
    await expect(api.get('/assistant/suggestions?page=runs')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('lässt andere Pfade durch', async () => {
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });
    await expect(api.get('/workflows')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('erlaubt nach Ablauf der Sperre genau eine neue Anfrage', async () => {
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });
    vi.setSystemTime(NOW + 5_000);
    expect(rateLimitRemainingSeconds('/system/capabilities')).toBe(0);

    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('nimmt 10 s, wenn Retry-After fehlt', async () => {
    fetchMock.mockImplementation(async () => jsonResponse(429, { error: 'nope', timestamp: 'x' }));
    await expect(api.get('/system/info')).rejects.toMatchObject({ retryAfterSeconds: 10 });
    expect(rateLimitRemainingSeconds('/system/info')).toBe(10);
  });

  it('liefert für abgewiesene Aufrufe eine verständliche Meldung über apiErrorMessage', async () => {
    await api.get('/system/capabilities').catch(() => undefined);
    const err = await api.get('/system/capabilities').catch((e: unknown) => e);
    expect(apiErrorMessage(err, 'Fallback')).toContain('Too many requests');
  });

  it('lässt Nicht-429-Fehler unverändert und sperrt nichts', async () => {
    fetchMock.mockImplementation(async () => jsonResponse(500, { error: 'boom', timestamp: 'x' }));
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 500, error: 'boom' });
    expect(rateLimitRemainingSeconds('/system/capabilities')).toBe(0);
    await api.get('/system/capabilities').catch(() => undefined);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
