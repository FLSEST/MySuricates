import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse, delay } from 'msw';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useIntegrationActionTest } from '../useIntegrationActionTest';
import type { IntegrationActionInfo } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: qc }, children);
}

const listRepos: IntegrationActionInfo = { name: 'list_repos', description: 'List repositories', inputSchema: {}, available: true };

describe('useIntegrationActionTest', () => {
  it('marks the running action and shows its result as indented JSON', async () => {
    const calls: string[] = [];
    server.use(http.post('/api/v1/integrations/:providerId/actions/:action', async ({ params }) => {
      calls.push(`${params.providerId}/${params.action}`);
      await delay(20);
      return HttpResponse.json({ repos: ['a'] });
    }));
    const { result } = renderHook(() => useIntegrationActionTest(), { wrapper: createWrapper() });

    act(() => result.current.runTest('github', listRepos));
    expect(result.current.testingAction).toBe('list_repos');
    expect(result.current.testResult).toBeNull();

    await waitFor(() => expect(result.current.testingAction).toBeNull());
    expect(result.current.testResult).toBe(JSON.stringify({ repos: ['a'] }, null, 2));
    expect(calls).toEqual(['github/list_repos']);
  });

  it('clears the old result when a new run starts and leaves it empty on an error', async () => {
    server.use(http.post('/api/v1/integrations/:providerId/actions/:action', () => HttpResponse.json({ ok: true })));
    const { result } = renderHook(() => useIntegrationActionTest(), { wrapper: createWrapper() });
    act(() => result.current.runTest('github', listRepos));
    await waitFor(() => expect(result.current.testResult).not.toBeNull());

    server.use(http.post('/api/v1/integrations/:providerId/actions/:action', () =>
      HttpResponse.json({ message: 'scope missing' }, { status: 403 })));
    act(() => result.current.runTest('github', listRepos));
    expect(result.current.testResult).toBeNull();
    await waitFor(() => expect(result.current.testingAction).toBeNull());
    expect(result.current.testResult).toBeNull();
  });
});
