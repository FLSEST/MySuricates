import type { ApiError } from '../types';
import { useSettingsStore } from '../stores/settingsStore';
import { getTranslations } from '../i18n';

const BASE_URL = '/api/v1';

/** Fallback lock time when the server sends a 429 without a usable `Retry-After`. */
const DEFAULT_RETRY_AFTER_SECONDS = 10;

/**
 * Short lock across *all* endpoints after any 429. A storm usually hits many paths at once
 * (≈300 GETs on `/executions/{id}` in the observed incident), and a per-path lock would let
 * the first call of every path through. Two seconds is enough to stop that fan-out without
 * making the app feel frozen (b6feccbc).
 */
const GLOBAL_BACKOFF_SECONDS = 2;

/** Backoff deadlines (epoch ms) per endpoint pattern. */
const rateLimitedUntil = new Map<string, number>();

/** Deadline (epoch ms) of the short all-endpoints lock; 0 = none. */
let globalRateLimitedUntil = 0;

/** Listeners for backoff changes (banner, query defaults). */
const backoffListeners = new Set<() => void>();

const UUID_SEGMENT = /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/g;

/**
 * Backoff key: the endpoint *pattern*, not the concrete URL — query string dropped (so
 * `?page=x` variants share one lock) and UUIDs replaced by `{id}` (so `/executions/<a>` and
 * `/executions/<b>` share one lock instead of opening one per run).
 */
function backoffKey(path: string): string {
  const q = path.indexOf('?');
  const withoutQuery = q === -1 ? path : path.slice(0, q);
  return withoutQuery.replace(UUID_SEGMENT, '{id}');
}

function notifyBackoffListeners(): void {
  for (const listener of backoffListeners) listener();
}

/** Records a 429: locks the endpoint pattern plus, briefly, everything else. */
function lockAfterRateLimit(path: string, retryAfterSeconds: number, now: number = Date.now()): void {
  if (retryAfterSeconds > 0) {
    rateLimitedUntil.set(backoffKey(path), now + retryAfterSeconds * 1000);
  }
  const globalUntil = now + Math.min(GLOBAL_BACKOFF_SECONDS, Math.max(retryAfterSeconds, 0)) * 1000;
  if (globalUntil > globalRateLimitedUntil) globalRateLimitedUntil = globalUntil;
  notifyBackoffListeners();
}

/**
 * Seconds to wait as told by a `Retry-After` header — either a delta in seconds or an
 * HTTP date. Missing or unparsable ⇒ {@link DEFAULT_RETRY_AFTER_SECONDS}; a value in the
 * past ⇒ 0 (no lock).
 */
export function parseRetryAfter(header: string | null | undefined, now: number = Date.now()): number {
  if (header === null || header === undefined) return DEFAULT_RETRY_AFTER_SECONDS;
  const raw = header.trim();
  if (raw === '') return DEFAULT_RETRY_AFTER_SECONDS;
  if (/^\d+$/.test(raw)) return Number(raw);
  const at = Date.parse(raw);
  if (Number.isNaN(at)) return DEFAULT_RETRY_AFTER_SECONDS;
  return Math.max(0, Math.ceil((at - now) / 1000));
}

/**
 * Remaining lock time for a path in seconds (0 = free) — the later of the endpoint
 * pattern's own deadline and the short all-endpoints lock.
 */
export function rateLimitRemainingSeconds(path: string, now: number = Date.now()): number {
  const key = backoffKey(path);
  const until = rateLimitedUntil.get(key);
  if (until !== undefined && now >= until) rateLimitedUntil.delete(key);
  const deadline = Math.max(until !== undefined && now < until ? until : 0, globalDeadline(now));
  return deadline > now ? Math.ceil((deadline - now) / 1000) : 0;
}

function globalDeadline(now: number): number {
  if (globalRateLimitedUntil !== 0 && now >= globalRateLimitedUntil) globalRateLimitedUntil = 0;
  return globalRateLimitedUntil;
}

/** Latest deadline (epoch ms) of any active backoff; 0 when nothing is locked. */
export function rateLimitBackoffUntil(now: number = Date.now()): number {
  let latest = globalDeadline(now);
  for (const [key, until] of rateLimitedUntil) {
    if (now >= until) rateLimitedUntil.delete(key);
    else if (until > latest) latest = until;
  }
  return latest;
}

/** True while any endpoint is still in its 429 backoff window. */
export function isRateLimitBackoffActive(now: number = Date.now()): boolean {
  return rateLimitBackoffUntil(now) > now;
}

/**
 * Notifies when a backoff starts. Expiry is not pushed (there is no timer here) — readers
 * that display the remaining time poll {@link rateLimitBackoffUntil} on their own tick.
 */
export function subscribeRateLimitBackoff(listener: () => void): () => void {
  backoffListeners.add(listener);
  return () => { backoffListeners.delete(listener); };
}

/** Clears every backoff deadline. Test helper — production code lets them expire. */
export function resetRateLimitBackoff(): void {
  rateLimitedUntil.clear();
  globalRateLimitedUntil = 0;
  notifyBackoffListeners();
}

export interface RateLimitError extends ApiError {
  status: 429;
  code: 'RATE_LIMITED';
  message: string;
  /** Seconds the client will keep refusing this path. */
  retryAfterSeconds: number;
}

function rateLimitError(retryAfterSeconds: number, serverMessage?: string): RateLimitError {
  const message = serverMessage && serverMessage.length > 0
    ? serverMessage
    : getTranslations().errors.rateLimited;
  return {
    error: message,
    message,
    code: 'RATE_LIMITED',
    status: 429,
    retryAfterSeconds,
    timestamp: new Date().toISOString(),
  };
}

class ApiClient {
  private getHeaders(): HeadersInit {
    const headers: HeadersInit = { 'Content-Type': 'application/json' };
    const apiKey = useSettingsStore.getState().apiKey;
    if (apiKey) {
      headers['X-API-Key'] = apiKey;
    }
    return headers;
  }

  private async request<T>(path: string, options?: RequestInit, responseType: 'json' | 'text' = 'json'): Promise<T> {
    // A 429 locks the path for `Retry-After`: further calls fail fast instead of
    // topping the rate limit up again (d89b2cc1).
    const blockedFor = rateLimitRemainingSeconds(path);
    if (blockedFor > 0) throw rateLimitError(blockedFor);

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 300_000);
    try {
      const response = await fetch(`${BASE_URL}${path}`, {
        ...options,
        signal: options?.signal ?? controller.signal,
        headers: { ...this.getHeaders(), ...options?.headers },
      });

      if (!response.ok) {
        const body: ApiError = await response.json().catch(() => ({
          error: `HTTP ${response.status}: ${response.statusText}`,
          timestamp: new Date().toISOString(),
        }));
        if (response.status === 429) {
          const seconds = parseRetryAfter(response.headers.get('Retry-After'));
          lockAfterRateLimit(path, seconds);
          throw rateLimitError(seconds, body.error);
        }
        throw { ...body, status: response.status, message: body.error };
      }

      if (response.status === 204) return undefined as T;
      const text = await response.text();
      if (responseType === 'text') return text as T;
      if (!text) return undefined as T;
      return JSON.parse(text);
    } finally {
      clearTimeout(timeout);
    }
  }

  get<T>(path: string, options?: RequestInit): Promise<T> {
    return this.request<T>(path, options);
  }

  /** GET whose answer is not JSON (BPMN-XML) — same backoff, same error shape. */
  getText(path: string, options?: RequestInit): Promise<string> {
    return this.request<string>(path, options, 'text');
  }

  /** POST with a raw (non-JSON) body, e.g. the BPMN import's XML. */
  postRaw<T>(path: string, body: string, contentType: string): Promise<T> {
    return this.request<T>(path, {
      method: 'POST',
      body,
      headers: { 'Content-Type': contentType },
    });
  }

  post<T>(path: string, body?: unknown, options?: RequestInit): Promise<T> {
    return this.request<T>(path, {
      method: 'POST',
      body: body ? JSON.stringify(body) : undefined,
      ...options,
    });
  }

  put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>(path, {
      method: 'PUT',
      body: body ? JSON.stringify(body) : undefined,
    });
  }

  patch<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>(path, {
      method: 'PATCH',
      body: body ? JSON.stringify(body) : undefined,
    });
  }

  delete<T>(path: string): Promise<T> {
    return this.request<T>(path, { method: 'DELETE' });
  }

  /**
   * Holt ein kurzlebiges Einmal-Ticket für den WebSocket-Handshake. Der API-Key reist dabei im
   * Header (wie überall); nur das Ticket landet in der Handshake-URL — weder SockJS noch ein rohes
   * Browser-WebSocket können beim Handshake eigene Header setzen.
   */
  async fetchWsTicket(): Promise<string> {
    const response = await this.post<{ ticket: string; expiresInSeconds: number }>('/ws-ticket');
    return response.ticket;
  }
}

export const api = new ApiClient();

export interface AuthStatus {
  securityEnabled: boolean;
}

let authStatusPromise: Promise<AuthStatus> | null = null;

/**
 * Auth status for the guards (ApiKeyGuard, SecurityBanner). Whether security is on only
 * changes when the backend restarts, so the answer is cached for the app's lifetime and
 * concurrent callers share one request — two guards mounting together must not produce
 * two calls (d89b2cc1). A failed call is not cached; the client-side 429 backoff keeps a
 * retry from reaching the network during the lock.
 */
export function fetchAuthStatus(): Promise<AuthStatus> {
  if (!authStatusPromise) {
    authStatusPromise = api.get<AuthStatus>('/system/auth-status').catch((err) => {
      authStatusPromise = null;
      throw err;
    });
  }
  return authStatusPromise;
}

/** Drops the cached auth status. Test helper. */
export function resetAuthStatusCache(): void {
  authStatusPromise = null;
}

export function openPath(path: string): Promise<{ status: string }> {
  return api.post('/system/open-path', { path });
}

/** Extract the error message from an API error response, with i18n fallback */
export function apiErrorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === 'object' && 'error' in err) {
    const msg = (err as { error: string }).error;
    if (typeof msg === 'string' && msg.length > 0) return `${fallback}: ${msg}`;
  }
  return fallback;
}

/**
 * Importiert einen BPMN-Prozess als neuen Workflow (Body = XML, kein JSON). Läuft über den
 * Client und damit über den 429-Backoff — ein roher `fetch` würde die Sperre umgehen (b6feccbc).
 */
export function importWorkflowBpmn(xml: string, projectId: string): Promise<{ id: string }> {
  return api.postRaw<{ id: string }>(
    `/workflows/import/bpmn?projectId=${encodeURIComponent(projectId)}`,
    xml,
    'application/xml',
  );
}

/** Lädt den BPMN-2.0-Export eines Workflows als XML-String (kein JSON, aber über den Backoff). */
export function fetchWorkflowBpmn(id: string): Promise<string> {
  return api.getText(`/workflows/${id}/export/bpmn`);
}

/**
 * Prüft einen eingegebenen API-Key gegen `/projects`. Der getippte Key ersetzt für diesen
 * einen Aufruf den gespeicherten; der 429-Backoff gilt wie überall (b6feccbc).
 */
export function validateApiKey(key: string): Promise<unknown> {
  return api.get('/projects', { headers: { 'X-API-Key': key } });
}
