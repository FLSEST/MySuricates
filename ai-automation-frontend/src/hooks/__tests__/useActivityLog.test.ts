import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useActivityLog } from '../useActivityLog';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useActivityLog', () => {
  it('fetches activity log successfully', async () => {
    const { result } = renderHook(() => useActivityLog(0, 20), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.content).toHaveLength(1);
    expect(result.current.data!.content[0].message).toBe('Test log');
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useActivityLog(0, 20), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});
