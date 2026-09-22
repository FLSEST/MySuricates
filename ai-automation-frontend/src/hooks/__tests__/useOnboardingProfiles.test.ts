import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import {
  useOnboardingProfiles,
  useOnboardingProfile,
  useCreateOnboardingProfile,
  useDeleteOnboardingProfile,
  useOnboardingProfileHistory,
} from '../useOnboardingProfiles';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useOnboardingProfiles', () => {
  it('fetches all profiles', async () => {
    server.use(
      http.get('/api/v1/onboarding-profiles', () =>
        HttpResponse.json([{ id: 'op1', key: 'base', name: { en: 'Base' }, type: 'BASE', builtIn: true }])),
    );
    const { result } = renderHook(() => useOnboardingProfiles(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/onboarding-profiles', () => HttpResponse.json([])),
    );
    const { result } = renderHook(() => useOnboardingProfiles(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useOnboardingProfile', () => {
  it('fetches a single profile', async () => {
    server.use(
      http.get('/api/v1/onboarding-profiles/:id', () =>
        HttpResponse.json({ id: 'op1', key: 'base', name: { en: 'Base' } })),
    );
    const { result } = renderHook(() => useOnboardingProfile('op1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('op1');
  });

  it('does not fetch when id is empty', () => {
    const { result } = renderHook(() => useOnboardingProfile(''), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useCreateOnboardingProfile', () => {
  it('creates a profile', async () => {
    server.use(
      http.post('/api/v1/onboarding-profiles', () =>
        HttpResponse.json({ id: 'op2', key: 'new', name: { en: 'New' } })),
    );
    const { result } = renderHook(() => useCreateOnboardingProfile(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ key: 'new', name: { en: 'New' }, description: { en: 'Desc' }, content: { de: '# Test', en: '# test' }, type: 'PROFILE' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('op2');
  });
});

describe('useDeleteOnboardingProfile', () => {
  it('deletes a profile', async () => {
    server.use(
      http.delete('/api/v1/onboarding-profiles/:id', () =>
        new HttpResponse(null, { status: 204 })),
    );
    const { result } = renderHook(() => useDeleteOnboardingProfile(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('op1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useOnboardingProfileHistory', () => {
  it('fetches history for a profile', async () => {
    server.use(
      http.get('/api/v1/onboarding-profiles/:id/history', () =>
        HttpResponse.json([{ id: 'h1', version: 1, content: { de: 'v1' }, changedBy: 'user' }])),
    );
    const { result } = renderHook(() => useOnboardingProfileHistory('op1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
  });

  it('does not fetch when profileId is empty', () => {
    const { result } = renderHook(() => useOnboardingProfileHistory(''), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });
});
