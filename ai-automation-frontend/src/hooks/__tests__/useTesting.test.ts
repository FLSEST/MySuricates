import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import {
  useTestingStatus,
  useTestingStart,
  useTestingStop,
  useTestingReset,
  useTestingSyncPreview,
  useTestingSync,
  useTestingPullChanges,
} from '../useTesting';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useTestingStatus', () => {
  it('fetches testing status', async () => {
    server.use(
      http.get('/api/v1/testing/status', () =>
        HttpResponse.json({ running: true, healthy: true })),
    );
    const { result } = renderHook(() => useTestingStatus(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({ running: true, healthy: true });
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/testing/status', () =>
        HttpResponse.json({ running: false, healthy: false })),
    );
    const { result } = renderHook(() => useTestingStatus(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useTestingStart', () => {
  it('starts testing via mutation', async () => {
    server.use(
      http.post('/api/v1/testing/start', () => HttpResponse.json({ success: true })),
    );
    const { result } = renderHook(() => useTestingStart(), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate(); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useTestingStop', () => {
  it('stops testing via mutation', async () => {
    server.use(
      http.post('/api/v1/testing/stop', () => HttpResponse.json({ success: true })),
    );
    const { result } = renderHook(() => useTestingStop(), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate(); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useTestingReset', () => {
  it('resets testing via mutation', async () => {
    server.use(
      http.post('/api/v1/testing/reset', () => HttpResponse.json({ success: true })),
    );
    const { result } = renderHook(() => useTestingReset(), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate(); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useTestingSyncPreview', () => {
  it('fetches sync preview when enabled', async () => {
    server.use(
      http.get('/api/v1/testing/sync-preview', () =>
        HttpResponse.json({ workflows: 1, skills: 0, projects: 1 })),
    );
    const { result } = renderHook(() => useTestingSyncPreview(true), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({ workflows: 1, skills: 0, projects: 1 });
  });

  it('does not fetch when disabled', () => {
    const { result } = renderHook(() => useTestingSyncPreview(false), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useTestingSync', () => {
  it('syncs definitions via mutation', async () => {
    server.use(
      http.post('/api/v1/testing/sync-definitions', () =>
        HttpResponse.json({ synced: 2 })),
    );
    const { result } = renderHook(() => useTestingSync(), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate(); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({ synced: 2 });
  });
});

describe('useTestingPullChanges', () => {
  it('pulls changes via mutation', async () => {
    server.use(
      http.post('/api/v1/testing/pull-changes', () =>
        HttpResponse.json({ synced: 1 })),
    );
    const { result } = renderHook(() => useTestingPullChanges(), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate(); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({ synced: 1 });
  });
});
