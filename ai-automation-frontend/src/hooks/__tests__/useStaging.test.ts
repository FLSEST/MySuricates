import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import {
  useStagingStatus,
  useStagingStart,
  useStagingStop,
  useStagingPromote,
  useStagingCancel,
  useStagingSyncPreview,
  useStagingSync,
} from '../useStaging';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useStagingStatus', () => {
  it('fetches staging status', async () => {
    server.use(
      http.get('/api/v1/staging/status', () =>
        HttpResponse.json({ running: true, healthy: true, promotePhase: 'IDLE' })),
    );
    const { result } = renderHook(() => useStagingStatus(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({ running: true, healthy: true, promotePhase: 'IDLE' });
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/staging/status', () =>
        HttpResponse.json({ running: false, healthy: false, promotePhase: 'IDLE' })),
    );
    const { result } = renderHook(() => useStagingStatus(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useStagingStart', () => {
  it('starts staging via mutation', async () => {
    server.use(
      http.post('/api/v1/staging/start', () => HttpResponse.json({ success: true })),
    );
    const { result } = renderHook(() => useStagingStart(), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate(); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useStagingStop', () => {
  it('stops staging via mutation', async () => {
    server.use(
      http.post('/api/v1/staging/stop', () => HttpResponse.json({ success: true })),
    );
    const { result } = renderHook(() => useStagingStop(), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate(); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useStagingPromote', () => {
  it('promotes staging via mutation', async () => {
    server.use(
      http.post('/api/v1/staging/promote', () => HttpResponse.json({ success: true })),
    );
    const { result } = renderHook(() => useStagingPromote(), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate(); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useStagingCancel', () => {
  it('cancels promote via mutation', async () => {
    server.use(
      http.post('/api/v1/staging/promote/cancel', () => HttpResponse.json({ success: true })),
    );
    const { result } = renderHook(() => useStagingCancel(), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate(); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useStagingSyncPreview', () => {
  it('fetches sync preview when enabled', async () => {
    server.use(
      http.get('/api/v1/staging/sync-preview', () =>
        HttpResponse.json({ workflows: 2, skills: 1, projects: 1 })),
    );
    const { result } = renderHook(() => useStagingSyncPreview(true), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({ workflows: 2, skills: 1, projects: 1 });
  });

  it('does not fetch when disabled', () => {
    const { result } = renderHook(() => useStagingSyncPreview(false), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useStagingSync', () => {
  it('syncs definitions via mutation', async () => {
    server.use(
      http.post('/api/v1/staging/sync-definitions', () =>
        HttpResponse.json({ synced: 3 })),
    );
    const { result } = renderHook(() => useStagingSync(), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate(); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({ synced: 3 });
  });
});
