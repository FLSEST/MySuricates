import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useSystemHealth, useSystemInfo, useAiProviders, useBackupList, useExecutionConfig, useUpdateExecutionConfig, useUpdateSystemSetting } from '../useSystem';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useSystemHealth', () => {
  it('fetches system health', async () => {
    const { result } = renderHook(() => useSystemHealth(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.status).toBe('UP');
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useSystemHealth(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useSystemInfo', () => {
  it('fetches system info', async () => {
    const { result } = renderHook(() => useSystemInfo(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.version).toBe('0.5.0');
  });
});

describe('useAiProviders', () => {
  it('fetches AI providers', async () => {
    const { result } = renderHook(() => useAiProviders(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.providers).toContain('CLAUDE');
    expect(result.current.data!.default).toBe('CLAUDE');
  });
});

describe('useBackupList', () => {
  it('fetches backup list', async () => {
    const { result } = renderHook(() => useBackupList(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].name).toBe('backup1');
  });
});

describe('useExecutionConfig', () => {
  it('fetches execution config', async () => {
    const { result } = renderHook(() => useExecutionConfig(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.claudeMaxConcurrentCalls).toBe(5);
  });
});

describe('useUpdateExecutionConfig', () => {
  it('updates execution config via mutation', async () => {
    const { result } = renderHook(() => useUpdateExecutionConfig(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ executionMaxConcurrent: 5, claudeMaxConcurrentCalls: 5, claudeReservedForInteractive: 1, claudeTimeoutSeconds: 300, claudeInterCallDelayMs: 0, chainWorkflowTimeoutMinutes: 30, rateLimitEnabled: 1, rateLimitRequestsPerMinute: 120, assistantChatTimeoutSeconds: 240 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useUpdateSystemSetting', () => {
  it('updates a system setting via mutation', async () => {
    const { result } = renderHook(() => useUpdateSystemSetting(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ key: 'test', value: 'val' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
