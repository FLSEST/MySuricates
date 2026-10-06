import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { api } from '../client';
import { useSettingsStore } from '../../stores/settingsStore';

function fetchResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    text: async () => JSON.stringify(body),
    json: async () => body,
  };
}

describe('api.fetchWsTicket', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    useSettingsStore.setState({ apiKey: 'secret-key' });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    useSettingsStore.setState({ apiKey: '' });
  });

  it('fetches the ticket via POST with the key in the header — never in the URL', async () => {
    fetchMock.mockResolvedValue(fetchResponse(200, { ticket: 'tkt-42', expiresInSeconds: 30 }));

    await expect(api.fetchWsTicket()).resolves.toBe('tkt-42');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/ws-ticket');
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['X-API-Key']).toBe('secret-key');
    expect(url).not.toContain('apiKey');
  });

  it('passes an error through instead of returning an empty ticket', async () => {
    fetchMock.mockResolvedValue(fetchResponse(403, { error: 'Forbidden' }));

    await expect(api.fetchWsTicket()).rejects.toMatchObject({ status: 403 });
  });
});
