import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useI18nStore } from '../../i18n';
import { useTemplates, useCreateTemplate, useSaveWorkflowAsTemplate } from '../useTemplates';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
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

  it('asks for the steps of bilingual templates in the UI language', async () => {
    useI18nStore.setState({ locale: 'de' });
    let requested: string | null = null;
    server.use(http.get('/api/v1/workflow-templates', ({ request }) => {
      requested = new URL(request.url).searchParams.get('locale');
      return HttpResponse.json([]);
    }));

    const { result } = renderHook(() => useTemplates(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(requested).toBe('de');
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
