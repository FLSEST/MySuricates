import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { usePoolWorkflows, useInstallPoolWorkflow, usePoolExport } from '../usePool';
import { useToastStore } from '../../stores/toastStore';
import { useI18nStore } from '../../i18n';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('usePoolWorkflows', () => {
  it('fetches pool workflows', async () => {
    server.use(
      http.get('/api/v1/pool', () =>
        HttpResponse.json([{ id: 'p1', name: 'Pool Workflow 1' }])),
    );
    const { result } = renderHook(() => usePoolWorkflows(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/pool', () => HttpResponse.json([])),
    );
    const { result } = renderHook(() => usePoolWorkflows(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useInstallPoolWorkflow', () => {
  it('installs a pool workflow', async () => {
    server.use(
      http.post('/api/v1/pool/:poolId/install', () =>
        HttpResponse.json({ workflowId: 'w1', workflowName: 'Installed', created: true })),
    );
    const { result } = renderHook(() => useInstallPoolWorkflow(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ poolId: 'p1', projectId: 'proj1' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.workflowId).toBe('w1');
  });

  it('installs in the UI language', async () => {
    useI18nStore.setState({ locale: 'de' });
    let body: unknown = null;
    server.use(
      http.post('/api/v1/pool/:poolId/install', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ workflowId: 'w1', workflowName: 'Installiert', created: true });
      }),
    );
    const { result } = renderHook(() => useInstallPoolWorkflow(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ poolId: 'p1', projectId: 'proj1' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(body).toMatchObject({ projectId: 'proj1', locale: 'de' });
  });

  describe('integrity of the pool file (b18939a6)', () => {
    afterEach(() => useToastStore.setState({ toasts: [] }));

    async function install(response: Record<string, unknown>) {
      useI18nStore.setState({ locale: 'en' });
      useToastStore.setState({ toasts: [] });
      server.use(http.post('/api/v1/pool/:poolId/install', () => HttpResponse.json(response)));
      const { result } = renderHook(() => useInstallPoolWorkflow(), { wrapper: createWrapper() });
      await act(async () => {
        result.current.mutate({ poolId: 'p1', projectId: 'proj1' });
      });
      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      return useToastStore.getState().toasts;
    }

    it('warns when the file carries no checksum', async () => {
      const toasts = await install({ workflowId: 'w1', workflowName: 'Old', created: true, integrity: 'MISSING' });
      expect(toasts.map((toast) => toast.type)).toEqual(['success', 'warning']);
      expect(toasts[1].message).toMatch(/no checksum/);
    });

    it('does not warn for a verified file, nor for an older backend without the field', async () => {
      expect((await install({ workflowId: 'w1', workflowName: 'New', created: true, integrity: 'VERIFIED' }))
        .map((toast) => toast.type)).toEqual(['success']);
      expect((await install({ workflowId: 'w1', workflowName: 'Older', created: true }))
        .map((toast) => toast.type)).toEqual(['success']);
    });
  });
});

describe('usePoolExport', () => {
  afterEach(() => {
    useToastStore.setState({ toasts: [] });
  });

  it('sends the scope as a query parameter', async () => {
    let receivedUrl = '';
    server.use(
      http.post('/api/v1/pool/export/:workflowId', ({ request }) => {
        receivedUrl = request.url;
        return HttpResponse.json({ poolId: 'p1', filename: 'p1.json', sanitizedPaths: [] });
      }),
    );
    const { result } = renderHook(() => usePoolExport(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ workflowId: 'w1', scope: 'repo' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(new URL(receivedUrl).searchParams.get('scope')).toBe('repo');
  });

  it('defaults the scope query parameter to portable when not given', async () => {
    let receivedUrl = '';
    server.use(
      http.post('/api/v1/pool/export/:workflowId', ({ request }) => {
        receivedUrl = request.url;
        return HttpResponse.json({ poolId: 'p1', filename: 'p1.json', sanitizedPaths: [] });
      }),
    );
    const { result } = renderHook(() => usePoolExport(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ workflowId: 'w1' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(new URL(receivedUrl).searchParams.get('scope')).toBe('portable');
  });

  it('names the sanitized paths in the success toast when there are any', async () => {
    server.use(
      http.post('/api/v1/pool/export/:workflowId', () =>
        HttpResponse.json({ poolId: 'p1', filename: 'p1.json', sanitizedPaths: ['config.workflowId', 'blocks[2].config.projectId'] })),
    );
    const { result } = renderHook(() => usePoolExport(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ workflowId: 'w1', scope: 'portable' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const messages = useToastStore.getState().toasts.map(t => t.message);
    expect(messages.some(m => m.includes('config.workflowId') && m.includes('blocks[2].config.projectId'))).toBe(true);
  });

  it('does not mention sanitized paths in the toast when the list is empty', async () => {
    server.use(
      http.post('/api/v1/pool/export/:workflowId', () =>
        HttpResponse.json({ poolId: 'p1', filename: 'p1.json', sanitizedPaths: [] })),
    );
    const { result } = renderHook(() => usePoolExport(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ workflowId: 'w1', scope: 'portable' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const messages = useToastStore.getState().toasts.map(t => t.message);
    expect(messages.length).toBe(1);
    expect(messages[0]).not.toMatch(/config\.|blocks\[/);
  });
});
