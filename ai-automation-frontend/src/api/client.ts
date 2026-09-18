import type { ApiError } from '../types';
import { useSettingsStore } from '../stores/settingsStore';
import { getTranslations } from '../i18n';

const BASE_URL = '/api/v1';

/** Fallback lock time when the server sends a 429 without a usable `Retry-After`. */
const DEFAULT_RETRY_AFTER_SECONDS = 10;

/** Backoff deadlines (epoch ms) per request path, query string stripped. */
const rateLimitedUntil = new Map<string, number>();

/** Backoff key: the path without its query, so `?page=x` variants share one lock. */
function backoffKey(path: string): string {
  const q = path.indexOf('?');
  return q === -1 ? path : path.slice(0, q);
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

/** Remaining lock time for a path in seconds (0 = free). */
export function rateLimitRemainingSeconds(path: string, now: number = Date.now()): number {
  const until = rateLimitedUntil.get(backoffKey(path));
  if (until === undefined) return 0;
  if (now >= until) {
    rateLimitedUntil.delete(backoffKey(path));
    return 0;
  }
  return Math.ceil((until - now) / 1000);
}

/** Clears every backoff deadline. Test helper — production code lets them expire. */
export function resetRateLimitBackoff(): void {
  rateLimitedUntil.clear();
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

  private async request<T>(path: string, options?: RequestInit): Promise<T> {
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
          if (seconds > 0) rateLimitedUntil.set(backoffKey(path), Date.now() + seconds * 1000);
          throw rateLimitError(seconds, body.error);
        }
        throw { ...body, status: response.status, message: body.error };
      }

      if (response.status === 204) return undefined as T;
      const text = await response.text();
      if (!text) return undefined as T;
      return JSON.parse(text);
    } finally {
      clearTimeout(timeout);
    }
  }

  get<T>(path: string): Promise<T> {
    return this.request<T>(path);
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

/** Importiert einen BPMN-Prozess als neuen Workflow (Body = XML, kein JSON — daher raw fetch). */
export async function importWorkflowBpmn(xml: string, projectId: string): Promise<{ id: string }> {
  const headers: HeadersInit = { 'Content-Type': 'application/xml' };
  const apiKey = useSettingsStore.getState().apiKey;
  if (apiKey) headers['X-API-Key'] = apiKey;
  const res = await fetch(`${BASE_URL}/workflows/import/bpmn?projectId=${encodeURIComponent(projectId)}`, {
    method: 'POST',
    headers,
    body: xml,
  });
  if (!res.ok) {
    let body: unknown;
    try { body = await res.json(); } catch { body = undefined; }
    throw body ?? new Error(`BPMN import failed (${res.status})`);
  }
  return res.json();
}

/** Lädt den BPMN-2.0-Export eines Workflows als XML-String (kein JSON — daher raw fetch). */
export async function fetchWorkflowBpmn(id: string): Promise<string> {
  const headers: HeadersInit = {};
  const apiKey = useSettingsStore.getState().apiKey;
  if (apiKey) headers['X-API-Key'] = apiKey;
  const res = await fetch(`${BASE_URL}/workflows/${id}/export/bpmn`, { headers });
  if (!res.ok) throw new Error(`BPMN export failed (${res.status})`);
  return res.text();
}
