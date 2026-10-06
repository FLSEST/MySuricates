/** Unit tests for the 429 backoff of the API client (d89b2cc1, b6feccbc). */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  api,
  apiErrorMessage,
  applyServerRateLimitConfig,
  fetchWorkflowBpmn,
  importWorkflowBpmn,
  isRateLimitBackoffActive,
  parseRetryAfter,
  rateLimitBackoffUntil,
  rateLimitRemainingSeconds,
  resetRateLimitBackoff,
  subscribeRateLimitBackoff,
  validateApiKey,
} from '../client';

const NOW = Date.parse('2026-09-14T12:00:00.000Z');
const UUID_A = '11111111-2222-3333-4444-555555555555';
const UUID_B = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

/** The global short block after the first 429 (b6feccbc). */
const GLOBAL_LOCK_MS = 2_000;

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

  it('gives 0 for a date in the past', () => {
    expect(parseRetryAfter('Mon, 14 Sep 2026 11:59:50 GMT', NOW)).toBe(0);
  });

  it('takes 10 s if the header is missing or unreadable', () => {
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

  it('blocks the path after a 429 and rejects follow-up calls without a network request', async () => {
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429, code: 'RATE_LIMITED' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(rateLimitRemainingSeconds('/system/capabilities')).toBe(5);

    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429, code: 'RATE_LIMITED' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('blocks per path without query — query variants share the block', async () => {
    await expect(api.get('/assistant/suggestions?page=workflows')).rejects.toMatchObject({ status: 429 });
    await expect(api.get('/assistant/suggestions?page=runs')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('lets other paths through after the global short block', async () => {
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });
    // Directly after the first 429 the global short block applies (b6feccbc).
    await expect(api.get('/workflows')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);

    vi.setSystemTime(NOW + GLOBAL_LOCK_MS);
    await expect(api.get('/workflows')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('allows exactly one new request after the block expires', async () => {
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

  it('delivers an understandable message for rejected calls via apiErrorMessage', async () => {
    await api.get('/system/capabilities').catch(() => undefined);
    const err = await api.get('/system/capabilities').catch((e: unknown) => e);
    expect(apiErrorMessage(err, 'Fallback')).toContain('Too many requests');
  });

  it('leaves non-429 errors unchanged and blocks nothing', async () => {
    fetchMock.mockImplementation(async () => jsonResponse(500, { error: 'boom', timestamp: 'x' }));
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 500, error: 'boom' });
    expect(rateLimitRemainingSeconds('/system/capabilities')).toBe(0);
    await api.get('/system/capabilities').catch(() => undefined);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

describe('Sperre je Endpoint-Muster (b6feccbc)', () => {
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

  it('blocks /executions/<uuid2> as well after a 429 on /executions/<uuid1>', async () => {
    await expect(api.get(`/executions/${UUID_A}`)).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // Without pattern normalization this would be a separate block — and thus another request.
    await expect(api.get(`/executions/${UUID_B}`)).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(rateLimitRemainingSeconds(`/executions/${UUID_B}`)).toBe(5);
  });

  it('keeps different endpoint patterns apart (after the global short block)', async () => {
    await expect(api.get(`/executions/${UUID_A}`)).rejects.toMatchObject({ status: 429 });
    vi.setSystemTime(NOW + GLOBAL_LOCK_MS);

    // Anderes Muster ⇒ eigene Sperre, also genau eine weitere Anfrage.
    await expect(api.get(`/executions/${UUID_A}/steps`)).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('sets no block at all on Retry-After: 0', async () => {
    fetchMock.mockImplementation(async () => jsonResponse(429, { error: 'nope', timestamp: 'x' }, { 'Retry-After': '0' }));

    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429, retryAfterSeconds: 0 });
    expect(isRateLimitBackoffActive()).toBe(false);
    expect(rateLimitRemainingSeconds('/system/capabilities')).toBe(0);

    // Neither the own path nor a foreign one is blocked.
    await expect(api.get('/workflows')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

describe('Ausnahmen von der globalen Kurzsperre (b6feccbc)', () => {
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

  it('lets /ws-ticket and /system/auth-status through despite a foreign 429', async () => {
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // A blocked ticket costs the WebSocket one reconnect attempt, a blocked
    // auth-status lets the ApiKeyGuard render nothing — neither may hang here.
    await expect(api.fetchWsTicket()).rejects.toMatchObject({ status: 429 });
    await expect(api.get('/system/auth-status')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('sperrt die Ausnahme-Pfade nach ihrem eigenen 429 trotzdem', async () => {
    await expect(api.fetchWsTicket()).rejects.toMatchObject({ status: 429 });
    await expect(api.fetchWsTicket()).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(rateLimitRemainingSeconds('/ws-ticket')).toBe(5);

    await expect(api.get('/system/auth-status')).rejects.toMatchObject({ status: 429 });
    await expect(api.get('/system/auth-status')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

describe('Raw calls go through the backoff (b6feccbc)', () => {
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

  it('BPMN-Export: zweiter Aufruf im Fenster ohne Netzanfrage', async () => {
    await expect(fetchWorkflowBpmn(UUID_A)).rejects.toMatchObject({ status: 429, code: 'RATE_LIMITED' });
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await expect(fetchWorkflowBpmn(UUID_B)).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('BPMN-Import: im Fenster eines anderen 429 ohne Netzanfrage', async () => {
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });
    await expect(importWorkflowBpmn('<bpmn/>', 'p1')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('key check: no network request within the window', async () => {
    await expect(api.get('/projects')).rejects.toMatchObject({ status: 429 });
    await expect(validateApiKey('typed-key')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('BPMN export delivers the response text, not JSON', async () => {
    fetchMock.mockImplementation(async () => new Response('<definitions/>', {
      status: 200, headers: { 'Content-Type': 'application/xml' },
    }));
    await expect(fetchWorkflowBpmn(UUID_A)).resolves.toBe('<definitions/>');
  });

  it('BPMN-Import schickt XML als Body', async () => {
    fetchMock.mockImplementation(async () => jsonResponse(200, { id: 'new-id' }));
    await expect(importWorkflowBpmn('<bpmn/>', 'proj-1')).resolves.toMatchObject({ id: 'new-id' });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/workflows/import/bpmn?projectId=proj-1');
    expect(init.body).toBe('<bpmn/>');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/xml');
  });

  it('key check sends the entered key in the header', async () => {
    fetchMock.mockImplementation(async () => jsonResponse(200, []));
    await validateApiKey('typed-key');
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)['X-API-Key']).toBe('typed-key');
  });
});

describe('Backoff-Parameter vom Server (37b98c97)', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    resetRateLimitBackoff();
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    fetchMock = vi.fn(async () => jsonResponse(429, { error: 'Rate limit exceeded', timestamp: 'x' }));
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    resetRateLimitBackoff(); // stellt auch die eingebauten Fallback-Werte wieder her
  });

  it('nimmt ohne Serverwerte die eingebauten Konstanten (2 s / 10 s)', async () => {
    expect(parseRetryAfter(null, NOW)).toBe(10);

    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ retryAfterSeconds: 10 });
    // The global short block stays at 2 s although the path block stands at 10 s.
    vi.setSystemTime(NOW + 2_000);
    expect(rateLimitRemainingSeconds('/workflows')).toBe(0);
  });

  it('adopts the instance values from /system/info', async () => {
    applyServerRateLimitConfig({ globalBackoffSeconds: 5, defaultRetryAfterSeconds: 30 });

    expect(parseRetryAfter(null, NOW)).toBe(30);
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ retryAfterSeconds: 30 });
    expect(rateLimitRemainingSeconds('/system/capabilities')).toBe(30);

    // Fremder Pfad: jetzt 5 s statt 2 s gesperrt.
    expect(rateLimitRemainingSeconds('/workflows')).toBe(5);
    vi.setSystemTime(NOW + 2_000);
    expect(rateLimitRemainingSeconds('/workflows')).toBe(3);
    vi.setSystemTime(NOW + 5_000);
    expect(rateLimitRemainingSeconds('/workflows')).toBe(0);
  });

  it('ignores missing, unusable or non-positive server values', async () => {
    applyServerRateLimitConfig(undefined);
    expect(parseRetryAfter(null, NOW)).toBe(10);

    applyServerRateLimitConfig({ globalBackoffSeconds: 0, defaultRetryAfterSeconds: -5 });
    expect(parseRetryAfter(null, NOW)).toBe(10);

    applyServerRateLimitConfig({ defaultRetryAfterSeconds: Number.NaN } as never);
    expect(parseRetryAfter(null, NOW)).toBe(10);

    // Only the usable part is adopted.
    applyServerRateLimitConfig({ globalBackoffSeconds: 4 });
    expect(parseRetryAfter(null, NOW)).toBe(10);
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ retryAfterSeconds: 10 });
    expect(rateLimitRemainingSeconds('/workflows')).toBe(4);
  });

  it('deckelt die globale Sperre weiterhin durch Retry-After', async () => {
    applyServerRateLimitConfig({ globalBackoffSeconds: 5, defaultRetryAfterSeconds: 30 });
    fetchMock.mockImplementation(async () =>
      jsonResponse(429, { error: 'nope', timestamp: 'x' }, { 'Retry-After': '1' }));

    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ retryAfterSeconds: 1 });
    expect(rateLimitRemainingSeconds('/workflows')).toBe(1);
  });
});

describe('Backoff state for UI and query defaults (c168293d, f3fb193f)', () => {
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

  it('reports the active backoff and its end', async () => {
    expect(isRateLimitBackoffActive()).toBe(false);
    expect(rateLimitBackoffUntil()).toBe(0);

    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });

    expect(isRateLimitBackoffActive()).toBe(true);
    expect(rateLimitBackoffUntil()).toBe(NOW + 5_000);

    vi.setSystemTime(NOW + 5_000);
    expect(isRateLimitBackoffActive()).toBe(false);
    expect(rateLimitBackoffUntil()).toBe(0);
  });

  it('notifies subscribers when a block begins', async () => {
    const listener = vi.fn();
    const unsubscribe = subscribeRateLimitBackoff(listener);

    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });
    expect(listener).toHaveBeenCalled();

    unsubscribe();
    listener.mockClear();
    vi.setSystemTime(NOW + 6_000);
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });
    expect(listener).not.toHaveBeenCalled();
  });

  it('also reports the expiry of the last block, without anyone polling', async () => {
    const listener = vi.fn();
    const unsubscribe = subscribeRateLimitBackoff(listener);
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });
    listener.mockClear();

    await vi.advanceTimersByTimeAsync(5_000);

    expect(listener).toHaveBeenCalled();
    expect(isRateLimitBackoffActive()).toBe(false);
    unsubscribe();
  });
});
