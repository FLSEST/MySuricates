import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import {
  useIntegrations,
  useIntegration,
  useConnectIntegration,
  useDisconnectIntegration,
  useTestIntegrationAction,
  connectionParam,
} from '../useIntegrations';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => {
  server.resetHandlers();
  vi.restoreAllMocks();
});
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

const apiBase = '/api/v1';

describe('useIntegrations', () => {
  it('should_fetch_all_integrations_when_called', async () => {
    server.use(
      http.get(`${apiBase}/integrations`, () =>
        HttpResponse.json([
          { id: 'github', name: 'GitHub', connected: true },
          { id: 'slack', name: 'Slack', connected: false },
        ])
      )
    );
    const { result } = renderHook(() => useIntegrations(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(2);
    expect(result.current.data![0].id).toBe('github');
  });

  it('should_propagate_error_when_api_returns_401', async () => {
    server.use(
      http.get(`${apiBase}/integrations`, () =>
        HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })
      )
    );
    const { result } = renderHook(() => useIntegrations(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toBeDefined();
  });
});

describe('useIntegration', () => {
  it('should_fetch_single_integration_when_provider_id_given', async () => {
    server.use(
      http.get(`${apiBase}/integrations/github`, () =>
        HttpResponse.json({ id: 'github', name: 'GitHub', connected: true })
      )
    );
    const { result } = renderHook(() => useIntegration('github'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('github');
  });

  it('should_be_disabled_when_provider_id_empty', () => {
    const { result } = renderHook(() => useIntegration(''), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.isLoading).toBe(false);
  });
});

describe('useConnectIntegration', () => {
  it('should_resolve_when_oauth_callback_message_received', async () => {
    server.use(
      http.get(`${apiBase}/integrations/github/connect`, () =>
        HttpResponse.json({ authorizationUrl: 'https://github.com/oauth/authorize?x=1' })
      )
    );
    const fakePopup = { closed: false } as Window;
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(fakePopup);

    const { result } = renderHook(() => useConnectIntegration(), { wrapper: createWrapper() });

    let mutationPromise: Promise<unknown> | undefined;
    act(() => {
      mutationPromise = result.current.mutateAsync({ providerId: 'github' });
    });

    // wait until popup was opened (i.e. authorization URL fetched)
    await waitFor(() => expect(openSpy).toHaveBeenCalled());

    // Simulate the OAuth popup posting the callback message
    window.dispatchEvent(new MessageEvent('message', { origin: window.location.origin, source: fakePopup,
      data: { type: 'oauth-callback', provider: 'github', status: 'connected' } }));

    await mutationPromise;
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(openSpy).toHaveBeenCalledWith(
      'https://github.com/oauth/authorize?x=1',
      'oauth-popup',
      expect.any(String)
    );
  });

  it('should_reject_when_popup_blocked', async () => {
    server.use(
      http.get(`${apiBase}/integrations/github/connect`, () =>
        HttpResponse.json({ authorizationUrl: 'https://example.com' })
      )
    );
    vi.spyOn(window, 'open').mockReturnValue(null);

    const { result } = renderHook(() => useConnectIntegration(), { wrapper: createWrapper() });

    await act(async () => {
      try {
        await result.current.mutateAsync({ providerId: 'github' });
      } catch {
        /* expected */
      }
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect((result.current.error as Error).message).toBe('Popup blocked');
  });

  it('should_propagate_error_when_connect_endpoint_returns_401', async () => {
    server.use(
      http.get(`${apiBase}/integrations/github/connect`, () =>
        HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })
      )
    );
    vi.spyOn(window, 'open').mockReturnValue({ closed: false } as Window);

    const { result } = renderHook(() => useConnectIntegration(), { wrapper: createWrapper() });

    await act(async () => {
      try {
        await result.current.mutateAsync({ providerId: 'github' });
      } catch {
        /* expected */
      }
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it('should_pass_scopes_as_query_params_when_provided', async () => {
    let receivedUrl = '';
    server.use(
      http.get(`${apiBase}/integrations/github/connect`, ({ request }) => {
        receivedUrl = request.url;
        return HttpResponse.json({ authorizationUrl: 'https://example.com' });
      })
    );
    const fakePopup = { closed: false } as Window;
    vi.spyOn(window, 'open').mockReturnValue(fakePopup);

    const { result } = renderHook(() => useConnectIntegration(), { wrapper: createWrapper() });

    let mutationPromise: Promise<unknown> | undefined;
    act(() => {
      mutationPromise = result.current.mutateAsync({ providerId: 'github', scopes: ['repo', 'user:email'] });
    });
    await waitFor(() => expect(receivedUrl).toContain('scopes=repo'));
    expect(receivedUrl).toContain('scopes=user%3Aemail');

    expect(new URL(receivedUrl).searchParams.get('frontendOrigin')).toBe(window.location.origin);
    window.dispatchEvent(new MessageEvent('message', { origin: window.location.origin, source: fakePopup,
      data: { type: 'oauth-callback', provider: 'github', status: 'connected' } }));
    await mutationPromise;
  });

  it('ignores forged messages and accepts the expected backend popup on a separate origin', async () => {
    server.use(http.get(`${apiBase}/integrations/github/connect`, () => HttpResponse.json({
      authorizationUrl: 'https://github.com/oauth/authorize', callbackOrigin: 'http://localhost:9090',
    })));
    const popup = { closed: false } as Window;
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(popup);
    const { result } = renderHook(() => useConnectIntegration(), { wrapper: createWrapper() });
    let done = false;
    let pending: Promise<unknown>;
    act(() => { pending = result.current.mutateAsync({ providerId: 'github' }).then(() => { done = true; }); });
    await waitFor(() => expect(openSpy).toHaveBeenCalled());
    const valid = { origin: 'http://localhost:9090', source: popup,
      data: { type: 'oauth-callback', provider: 'github', status: 'connected' } };
    for (const invalid of [
      { ...valid, origin: 'https://evil.example' },
      { ...valid, source: window },
      { ...valid, data: { ...valid.data, provider: 'jira' } },
      { ...valid, data: { ...valid.data, status: 'failed' } },
    ]) {
      await act(async () => { window.dispatchEvent(new MessageEvent('message', invalid)); });
      expect(done).toBe(false);
    }
    await act(async () => {
      window.dispatchEvent(new MessageEvent('message', valid));
      await pending;
    });
    expect(done).toBe(true);
  });
});

describe('useDisconnectIntegration', () => {
  it('should_delete_connection_when_called', async () => {
    let called = false;
    server.use(
      http.delete(`${apiBase}/integrations/github/connection`, () => {
        called = true;
        return new HttpResponse(null, { status: 204 });
      })
    );
    const { result } = renderHook(() => useDisconnectIntegration(), { wrapper: createWrapper() });
    await act(async () => {
      await result.current.mutateAsync('github');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(called).toBe(true);
  });

  it('should_propagate_error_when_api_returns_401', async () => {
    server.use(
      http.delete(`${apiBase}/integrations/github/connection`, () =>
        HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })
      )
    );
    const { result } = renderHook(() => useDisconnectIntegration(), { wrapper: createWrapper() });
    await act(async () => {
      try {
        await result.current.mutateAsync('github');
      } catch {
        /* expected */
      }
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe('named connections (0b3cbcc3)', () => {
  it('connectionParam omits the default connection and encodes a named one', () => {
    expect(connectionParam(undefined)).toBe('');
    expect(connectionParam('default')).toBe('');
    expect(connectionParam('work')).toBe('connection=work');
  });

  it('disconnect of a named connection sends ?connection=<name>', async () => {
    let url = '';
    server.use(
      http.delete(`${apiBase}/integrations/github/connection`, ({ request }) => {
        url = request.url;
        return new HttpResponse(null, { status: 204 });
      })
    );
    const { result } = renderHook(() => useDisconnectIntegration(), { wrapper: createWrapper() });
    await act(async () => {
      await result.current.mutateAsync({ providerId: 'github', connection: 'work' });
    });
    expect(new URL(url).searchParams.get('connection')).toBe('work');
  });

  it('test action on a named connection sends ?connection=<name>', async () => {
    let url = '';
    server.use(
      http.post(`${apiBase}/integrations/github/actions/list_repos`, ({ request }) => {
        url = request.url;
        return HttpResponse.json({ ok: true });
      })
    );
    const { result } = renderHook(() => useTestIntegrationAction(), { wrapper: createWrapper() });
    await act(async () => {
      await result.current.mutateAsync({ providerId: 'github', action: 'list_repos', connection: 'work' });
    });
    expect(new URL(url).searchParams.get('connection')).toBe('work');
  });
});

describe('useTestIntegrationAction', () => {
  it('should_post_action_with_params_when_called', async () => {
    let receivedBody: unknown = null;
    server.use(
      http.post(`${apiBase}/integrations/github/actions/listRepos`, async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json({ repos: ['a', 'b'] });
      })
    );
    const { result } = renderHook(() => useTestIntegrationAction(), { wrapper: createWrapper() });
    await act(async () => {
      await result.current.mutateAsync({
        providerId: 'github',
        action: 'listRepos',
        params: { user: 'flo' },
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(receivedBody).toEqual({ user: 'flo' });
    expect(result.current.data).toEqual({ repos: ['a', 'b'] });
  });

  it('should_post_empty_object_when_params_omitted', async () => {
    let receivedBody: unknown = null;
    server.use(
      http.post(`${apiBase}/integrations/slack/actions/sendMessage`, async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json({ ok: true });
      })
    );
    const { result } = renderHook(() => useTestIntegrationAction(), { wrapper: createWrapper() });
    await act(async () => {
      await result.current.mutateAsync({ providerId: 'slack', action: 'sendMessage' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(receivedBody).toEqual({});
  });

  it('should_propagate_error_when_api_returns_401', async () => {
    server.use(
      http.post(`${apiBase}/integrations/github/actions/listRepos`, () =>
        HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })
      )
    );
    const { result } = renderHook(() => useTestIntegrationAction(), { wrapper: createWrapper() });
    await act(async () => {
      try {
        await result.current.mutateAsync({ providerId: 'github', action: 'listRepos' });
      } catch {
        /* expected */
      }
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});
