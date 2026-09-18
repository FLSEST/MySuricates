/** Unit tests für den 429-Backoff des API-Clients (d89b2cc1, b6feccbc). */
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

/** Die globale Kurzsperre nach dem ersten 429 (b6feccbc). */
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

  it('lässt andere Pfade nach der globalen Kurzsperre durch', async () => {
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });
    // Direkt nach dem ersten 429 greift die globale Kurzsperre (b6feccbc).
    await expect(api.get('/workflows')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);

    vi.setSystemTime(NOW + GLOBAL_LOCK_MS);
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

  it('sperrt nach 429 auf /executions/<uuid1> auch /executions/<uuid2>', async () => {
    await expect(api.get(`/executions/${UUID_A}`)).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // Ohne Muster-Normalisierung wäre das eine eigene Sperre — und damit eine weitere Anfrage.
    await expect(api.get(`/executions/${UUID_B}`)).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(rateLimitRemainingSeconds(`/executions/${UUID_B}`)).toBe(5);
  });

  it('hält verschiedene Endpoint-Muster auseinander (nach der globalen Kurzsperre)', async () => {
    await expect(api.get(`/executions/${UUID_A}`)).rejects.toMatchObject({ status: 429 });
    vi.setSystemTime(NOW + GLOBAL_LOCK_MS);

    // Anderes Muster ⇒ eigene Sperre, also genau eine weitere Anfrage.
    await expect(api.get(`/executions/${UUID_A}/steps`)).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('setzt bei Retry-After: 0 überhaupt keine Sperre', async () => {
    fetchMock.mockImplementation(async () => jsonResponse(429, { error: 'nope', timestamp: 'x' }, { 'Retry-After': '0' }));

    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429, retryAfterSeconds: 0 });
    expect(isRateLimitBackoffActive()).toBe(false);
    expect(rateLimitRemainingSeconds('/system/capabilities')).toBe(0);

    // Weder der eigene Pfad noch ein fremder ist gesperrt.
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

  it('lässt /ws-ticket und /system/auth-status trotz fremder 429 durch', async () => {
    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // Ein blockiertes Ticket kostet den WebSocket einen Reconnect-Versuch, ein blockierter
    // auth-status lässt den ApiKeyGuard nichts rendern — beide dürfen hier nicht hängen.
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

describe('Rohe Aufrufe laufen über den Backoff (b6feccbc)', () => {
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

  it('Key-Prüfung: im Fenster ohne Netzanfrage', async () => {
    await expect(api.get('/projects')).rejects.toMatchObject({ status: 429 });
    await expect(validateApiKey('typed-key')).rejects.toMatchObject({ status: 429 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('BPMN-Export liefert den Antworttext, kein JSON', async () => {
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

  it('Key-Prüfung schickt den eingegebenen Key im Header', async () => {
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
    // Die globale Kurzsperre bleibt bei 2 s, obwohl die Pfad-Sperre 10 s steht.
    vi.setSystemTime(NOW + 2_000);
    expect(rateLimitRemainingSeconds('/workflows')).toBe(0);
  });

  it('übernimmt die Werte der Instanz aus /system/info', async () => {
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

  it('ignoriert fehlende, unbrauchbare oder nicht-positive Serverwerte', async () => {
    applyServerRateLimitConfig(undefined);
    expect(parseRetryAfter(null, NOW)).toBe(10);

    applyServerRateLimitConfig({ globalBackoffSeconds: 0, defaultRetryAfterSeconds: -5 });
    expect(parseRetryAfter(null, NOW)).toBe(10);

    applyServerRateLimitConfig({ defaultRetryAfterSeconds: Number.NaN } as never);
    expect(parseRetryAfter(null, NOW)).toBe(10);

    // Nur der brauchbare Teil wird übernommen.
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

describe('Backoff-Zustand für UI und Query-Defaults (c168293d, f3fb193f)', () => {
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

  it('meldet den aktiven Backoff und sein Ende', async () => {
    expect(isRateLimitBackoffActive()).toBe(false);
    expect(rateLimitBackoffUntil()).toBe(0);

    await expect(api.get('/system/capabilities')).rejects.toMatchObject({ status: 429 });

    expect(isRateLimitBackoffActive()).toBe(true);
    expect(rateLimitBackoffUntil()).toBe(NOW + 5_000);

    vi.setSystemTime(NOW + 5_000);
    expect(isRateLimitBackoffActive()).toBe(false);
    expect(rateLimitBackoffUntil()).toBe(0);
  });

  it('benachrichtigt Abonnenten, wenn eine Sperre beginnt', async () => {
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

  it('meldet auch den Ablauf der letzten Sperre, ohne dass jemand pollt', async () => {
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
