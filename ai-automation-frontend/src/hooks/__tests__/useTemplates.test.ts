import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useTemplates, useCreateTemplate, useSaveWorkflowAsTemplate } from '../useTemplates';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useTemplates', () => {
  it('fetches templates successfully', async () => {
    const { result } = renderHook(() => useTemplates(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].id).toBe('tmpl1');
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useTemplates(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useCreateTemplate', () => {
  it('creates a template via mutation', async () => {
    const { result } = renderHook(() => useCreateTemplate(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ name: '{"de":"Neu","en":"New"}', category: 'CODE', blocks: [] } as any);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('tmpl2');
  });
});

describe('useSaveWorkflowAsTemplate', () => {
  it('saves a workflow as template via mutation', async () => {
    const { result } = renderHook(() => useSaveWorkflowAsTemplate(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('w1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('tmpl3');
  });
});
