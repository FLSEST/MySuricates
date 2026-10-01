import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useReportsList, useReportContent, ReportEntry } from '../useReports';

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children);
  };
}

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('useReportsList', () => {
  it('initial state is loading with no data', () => {
    server.use(
      http.get('/api/v1/reports', async () => {
        await new Promise(r => setTimeout(r, 50));
        return HttpResponse.json([]);
      })
    );
    const { result } = renderHook(() => useReportsList(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
    expect(result.current.data).toBeUndefined();
  });

  it('returns the list of reports on success', async () => {
    const reports: ReportEntry[] = [
      { name: 'r1.md', label: 'Report 1' },
      { name: 'r2.md', label: 'Report 2' },
    ];
    server.use(http.get('/api/v1/reports', () => HttpResponse.json(reports)));
    const { result } = renderHook(() => useReportsList(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(reports);
    expect(result.current.isLoading).toBe(false);
  });

  it('returns empty array when API returns no reports', async () => {
    server.use(http.get('/api/v1/reports', () => HttpResponse.json([])));
    const { result } = renderHook(() => useReportsList(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([]);
  });

  it('exposes error state on API failure', async () => {
    server.use(
      http.get('/api/v1/reports', () =>
        HttpResponse.json({ error: 'boom' }, { status: 500 })
      )
    );
    const { result } = renderHook(() => useReportsList(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
    expect(result.current.error).toBeDefined();
  });
});

describe('useReportContent', () => {
  it('is disabled (does not fetch) when name is null', () => {
    const { result } = renderHook(() => useReportContent(null), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.data).toBeUndefined();
  });

  it('returns unwrapped report content on success', async () => {
    server.use(
      http.get('/api/v1/reports/r1.md', () =>
        HttpResponse.json({ content: '# Hello' })
      )
    );
    const { result } = renderHook(() => useReportContent('r1.md'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBe('# Hello');
  });

  it('URL-encodes the report name', async () => {
    server.use(
      http.get('/api/v1/reports/with%20space.md', () =>
        HttpResponse.json({ content: 'spaced' })
      )
    );
    const { result } = renderHook(() => useReportContent('with space.md'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBe('spaced');
  });

  it('exposes error state on API failure', async () => {
    server.use(
      http.get('/api/v1/reports/missing.md', () =>
        HttpResponse.json({ error: 'not found' }, { status: 404 })
      )
    );
    const { result } = renderHook(() => useReportContent('missing.md'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
    expect(result.current.error).toBeDefined();
  });
});
