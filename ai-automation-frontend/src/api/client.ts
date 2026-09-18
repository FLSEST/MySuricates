import type { ApiError } from '../types';
import { useSettingsStore } from '../stores/settingsStore';

const BASE_URL = '/api/v1';

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
