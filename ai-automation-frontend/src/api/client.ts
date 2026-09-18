import type { ApiError } from '../types';
import { useSettingsStore } from '../stores/settingsStore';
import { getTranslations } from '../i18n';

const BASE_URL = '/api/v1';

/**
 * Fallback lock time when the server sends a 429 without a usable `Retry-After`. Only the
 * fallback: the instance's own value arrives via {@link applyServerRateLimitConfig}.
 */
const DEFAULT_RETRY_AFTER_SECONDS = 10;

/**
 * Short lock across *all* endpoints after any 429. A storm usually hits many paths at once
 * (≈300 GETs on `/executions/{id}` in the observed incident), and a per-path lock would let
 * the first call of every path through. Two seconds is enough to stop that fan-out without
 * making the app feel frozen (b6feccbc).
 */
const GLOBAL_BACKOFF_SECONDS = 2;

/** The two numbers actually in use — server values when the instance sent them, else the constants. */
let defaultRetryAfterSeconds = DEFAULT_RETRY_AFTER_SECONDS;
let globalBackoffSeconds = GLOBAL_BACKOFF_SECONDS;

/** What `GET /system/info` reports about this instance's rate limit (all fields optional). */
export interface ServerRateLimitConfig {
  enabled?: boolean;
  requestsPerMinute?: number;
  globalBackoffSeconds?: number;
  defaultRetryAfterSeconds?: number;
}

/** A usable server number: finite and positive. Anything else keeps the constant. */
function usableSeconds(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null;
}

/**
 * Takes the backoff budget from the instance the app runs against (`GET /system/info` →
 * `rateLimit`). Another instance may run a different `requests-per-minute`, which makes the
 * built-in constants guesswork; they stay as the fallback for anything the server omits or
 * sends unusably (37b98c97). Called from `useSystemInfo` — no request of its own.
 */
export function applyServerRateLimitConfig(config: ServerRateLimitConfig | null | undefined): void {
  globalBackoffSeconds = usableSeconds(config?.globalBackoffSeconds) ?? GLOBAL_BACKOFF_SECONDS;
  defaultRetryAfterSeconds = usableSeconds(config?.defaultRetryAfterSeconds) ?? DEFAULT_RETRY_AFTER_SECONDS;
}

/**
 * Endpoints the *global* lock must not touch. Their own path lock still applies — this only
 * says that somebody else's 429 may not take them down with it:
 *  - `/ws-ticket`: a blocked ticket costs the WebSocket one reconnect attempt and doubles
 *    its exponential delay, so a 2 s lock turns into a much longer outage.
 *  - `/system/auth-status`: the ApiKeyGuard renders nothing until this answers; blocking it
 *    would show an empty app instead of the rate-limit hint.
 */
const GLOBAL_LOCK_EXEMPT = new Set(['/ws-ticket', '/system/auth-status']);

/** Backoff deadlines (epoch ms) per endpoint pattern. */
const rateLimitedUntil = new Map<string, number>();

/** Deadline (epoch ms) of the short all-endpoints lock; 0 = none. */
let globalRateLimitedUntil = 0;

/** Fires when the last deadline passes, so the app can recover without a reload. */
let expiryTimer: ReturnType<typeof setTimeout> | null = null;

/** Listeners for backoff changes (banner, query recovery). */
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

/** Latest deadline (epoch ms) currently stored — expired entries included. */
function latestDeadline(): number {
  let latest = globalRateLimitedUntil;
  for (const until of rateLimitedUntil.values()) if (until > latest) latest = until;
  return latest;
}

/** Drops deadlines that have passed. Housekeeping only — the readers check the clock. */
function pruneExpired(now: number): void {
  for (const [key, until] of rateLimitedUntil) if (now >= until) rateLimitedUntil.delete(key);
  if (globalRateLimitedUntil !== 0 && now >= globalRateLimitedUntil) globalRateLimitedUntil = 0;
}

/**
 * Arms a single timer on the last deadline. Without it nothing recovers: `retry` is off for
 * 429 and `refetchOnMount` only fires on a mount, so permanently mounted queries (sidebar,
 * layout, dashboard) would stay in their error state until a reload.
 */
function scheduleExpiry(now: number): void {
  if (expiryTimer !== null) {
    clearTimeout(expiryTimer);
    expiryTimer = null;
  }
  const latest = latestDeadline();
  if (latest <= now) return;
  expiryTimer = setTimeout(() => {
    expiryTimer = null;
    const at = Date.now();
    pruneExpired(at);
    notifyBackoffListeners();
    scheduleExpiry(at);
  }, latest - now);
}

/** Records a 429: locks the endpoint pattern plus, briefly, everything else. */
function lockAfterRateLimit(path: string, retryAfterSeconds: number, now: number = Date.now()): void {
  pruneExpired(now);
  if (retryAfterSeconds > 0) {
    rateLimitedUntil.set(backoffKey(path), now + retryAfterSeconds * 1000);
  }
  const globalUntil = now + Math.min(globalBackoffSeconds, Math.max(retryAfterSeconds, 0)) * 1000;
  if (globalUntil > globalRateLimitedUntil) globalRateLimitedUntil = globalUntil;
  scheduleExpiry(now);
  notifyBackoffListeners();
}

/**
 * Seconds to wait as told by a `Retry-After` header — either a delta in seconds or an
 * HTTP date. Missing or unparsable ⇒ the instance's default (server value, else
 * {@link DEFAULT_RETRY_AFTER_SECONDS}); a value in the past ⇒ 0 (no lock).
 */
export function parseRetryAfter(header: string | null | undefined, now: number = Date.now()): number {
  if (header === null || header === undefined) return defaultRetryAfterSeconds;
  const raw = header.trim();
  if (raw === '') return defaultRetryAfterSeconds;
  if (/^\d+$/.test(raw)) return Number(raw);
  const at = Date.parse(raw);
  if (Number.isNaN(at)) return defaultRetryAfterSeconds;
  return Math.max(0, Math.ceil((at - now) / 1000));
}

/**
 * Remaining lock time for a path in seconds (0 = free) — the later of the endpoint
 * pattern's own deadline and, unless the path is exempt, the short all-endpoints lock.
 * Pure reader: expired deadlines are dropped by the expiry timer, not here.
 */
export function rateLimitRemainingSeconds(path: string, now: number = Date.now()): number {
  const key = backoffKey(path);
  const own = rateLimitedUntil.get(key) ?? 0;
  const global = GLOBAL_LOCK_EXEMPT.has(key) ? 0 : globalRateLimitedUntil;
  const deadline = Math.max(own, global);
  return deadline > now ? Math.ceil((deadline - now) / 1000) : 0;
}

/** Latest deadline (epoch ms) of any active backoff; 0 when nothing is locked. */
export function rateLimitBackoffUntil(now: number = Date.now()): number {
  const latest = latestDeadline();
  return latest > now ? latest : 0;
}

/** True while any endpoint is still in its 429 backoff window. */
export function isRateLimitBackoffActive(now: number = Date.now()): boolean {
  return rateLimitBackoffUntil(now) > now;
}

/**
 * Notifies on every change of the backoff state — both when a lock starts and when the last
 * deadline passes (an internal timer pushes that flank). Readers therefore need no tick of
 * their own.
 */
export function subscribeRateLimitBackoff(listener: () => void): () => void {
  backoffListeners.add(listener);
  return () => { backoffListeners.delete(listener); };
}

/**
 * Clears every backoff deadline and restores the built-in fallback parameters. Test helper —
 * production code lets the deadlines expire and keeps the server's parameters.
 */
export function resetRateLimitBackoff(): void {
  rateLimitedUntil.clear();
  globalRateLimitedUntil = 0;
  applyServerRateLimitConfig(null);
  if (expiryTimer !== null) {
    clearTimeout(expiryTimer);
    expiryTimer = null;
  }
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
