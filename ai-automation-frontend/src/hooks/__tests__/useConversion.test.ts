import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useConversionSuggestion, useCreateWorkflowFromSuggestion } from '../useConversion';
import type { BlockDefinition } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useConversionSuggestion', () => {
  it('fetches conversion suggestion for a run', async () => {
    server.use(
      http.get('/api/v1/conversions/runs/:runId/suggest', () =>
        HttpResponse.json({ workflowName: 'Suggested WF', blocks: [] })),
    );
    const { result } = renderHook(() => useConversionSuggestion('r1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.workflowName).toBe('Suggested WF');
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/conversions/runs/:runId/suggest', () =>
        HttpResponse.json({ workflowName: '', blocks: [] })),
    );
    const { result } = renderHook(() => useConversionSuggestion('r1'), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });

  it('does not fetch when runId is empty', () => {
    const { result } = renderHook(() => useConversionSuggestion(''), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });

  it('does not fetch when disabled', () => {
    const { result } = renderHook(() => useConversionSuggestion('r1', false), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useCreateWorkflowFromSuggestion', () => {
  it('creates workflow from suggestion', async () => {
    server.use(
      http.post('/api/v1/conversions/runs/:runId/create-workflow', () =>
        HttpResponse.json({ id: 'w1', name: 'Created WF' })),
    );
    const { result } = renderHook(() => useCreateWorkflowFromSuggestion(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ runId: 'r1', projectId: 'p1' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('w1');
  });

  it('sends the reviewed suggestion so the server saves exactly what was shown (TODO 935af6d3)', async () => {
    let body: unknown;
    server.use(
      http.post('/api/v1/conversions/runs/:runId/create-workflow', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ id: 'w1', name: 'Reviewed' });
      }),
    );
    const blocks: BlockDefinition[] = [{ type: 'shell', config: { command: 'npm test' } }];
    const { result } = renderHook(() => useCreateWorkflowFromSuggestion(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({
        runId: 'r1', projectId: 'p1', name: 'Reviewed', description: 'From the preview', blocks,
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(body).toEqual({ projectId: 'p1', name: 'Reviewed', description: 'From the preview', blocks });
  });
});
