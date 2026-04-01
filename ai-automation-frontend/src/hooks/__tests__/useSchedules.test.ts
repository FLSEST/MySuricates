import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useSchedules, useMaintenanceMode, useSetMaintenanceMode, useCreateSchedule, useDeleteSchedule } from '../useSchedules';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useSchedules', () => {
  it('fetches schedules successfully', async () => {
    const { result } = renderHook(() => useSchedules(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].id).toBe('sc1');
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useSchedules(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useMaintenanceMode', () => {
  it('fetches maintenance mode status', async () => {
    const { result } = renderHook(() => useMaintenanceMode(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.enabled).toBe(false);
  });
});

describe('useSetMaintenanceMode', () => {
  it('toggles maintenance mode via mutation', async () => {
    const { result } = renderHook(() => useSetMaintenanceMode(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate(true);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useCreateSchedule', () => {
  it('creates a schedule via mutation', async () => {
    const { result } = renderHook(() => useCreateSchedule(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ workflowId: 'w1', cronExpression: '0 0 * * *' } as any);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('sc2');
  });
});

describe('useDeleteSchedule', () => {
  it('deletes a schedule via mutation', async () => {
    const { result } = renderHook(() => useDeleteSchedule(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('sc1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
