import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useDialogSessionCounts, useDialogSessions, useDialogProjects, useAnalyzeDialog, useCreateWorkflowFromDialog } from '../useDialog';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useDialogSessionCounts', () => {
  it('fetches session counts', async () => {
    server.use(
      http.get('/api/v1/dialog/sessions/count', () =>
        HttpResponse.json({ total: 5, analyzed: 2 })),
    );
    const { result } = renderHook(() => useDialogSessionCounts(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({ total: 5, analyzed: 2 });
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/dialog/sessions/count', () =>
        HttpResponse.json({ total: 0 })),
    );
    const { result } = renderHook(() => useDialogSessionCounts(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useDialogSessions', () => {
  it('fetches sessions', async () => {
    server.use(
      http.get('/api/v1/dialog/sessions', () =>
        HttpResponse.json([{ id: 's1', filePath: '/tmp/test.jsonl', tokenCount: 100 }])),
    );
    const { result } = renderHook(() => useDialogSessions(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].id).toBe('s1');
  });
});

describe('useDialogProjects', () => {
  it('fetches project paths', async () => {
    server.use(
      http.get('/api/v1/dialog/projects', () =>
        HttpResponse.json(['/home/user/project1', '/home/user/project2'])),
    );
    const { result } = renderHook(() => useDialogProjects(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(2);
  });
});

describe('useAnalyzeDialog', () => {
  it('analyzes a dialog session', async () => {
    server.use(
      http.post('/api/v1/dialog/sessions/analyze', () =>
        HttpResponse.json({ workflowName: 'Test Workflow', blocks: [] })),
    );
    const { result } = renderHook(() => useAnalyzeDialog(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('/tmp/test.jsonl');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.workflowName).toBe('Test Workflow');
  });
});

describe('useCreateWorkflowFromDialog', () => {
  it('creates workflow from suggestion', async () => {
    server.use(
      http.post('/api/v1/dialog/sessions/create-workflow', () =>
        HttpResponse.json({ id: 'w1', name: 'New Workflow' })),
    );
    const { result } = renderHook(() => useCreateWorkflowFromDialog(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({
        suggestion: { workflowName: 'Test', blocks: [] } as any,
        projectId: 'p1',
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('w1');
  });
});
