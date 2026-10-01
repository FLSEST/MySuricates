import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useDocsList, useGroupedDocs, useDocContent } from '../useDocs';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

const mockDocs = [
  { name: 'guide-de', label: 'Guide', lang: 'de' },
  { name: 'guide-en', label: 'Guide', lang: 'en' },
  { name: 'faq-de', label: 'FAQ', lang: 'de' },
];

describe('useDocsList', () => {
  it('fetches docs list', async () => {
    server.use(
      http.get('/api/v1/docs', () => HttpResponse.json(mockDocs)),
    );
    const { result } = renderHook(() => useDocsList(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(3);
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/docs', () => HttpResponse.json(mockDocs)),
    );
    const { result } = renderHook(() => useDocsList(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useGroupedDocs', () => {
  it('groups docs by label with de/en entries', async () => {
    server.use(
      http.get('/api/v1/docs', () => HttpResponse.json(mockDocs)),
    );
    const { result } = renderHook(() => useGroupedDocs(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.grouped).toHaveLength(2);
    expect(result.current.grouped[0]).toEqual({ label: 'Guide', de: 'guide-de', en: 'guide-en' });
    expect(result.current.grouped[1]).toEqual({ label: 'FAQ', de: 'faq-de', en: null });
  });
});

describe('useDocContent', () => {
  it('fetches doc content when name is provided', async () => {
    server.use(
      http.get('/api/v1/docs/guide-de', () =>
        HttpResponse.json({ content: '# Guide\nHello' })),
    );
    const { result } = renderHook(() => useDocContent('guide-de'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBe('# Guide\nHello');
  });

  it('does not fetch when name is null', () => {
    const { result } = renderHook(() => useDocContent(null), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });
});
