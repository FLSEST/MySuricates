import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useSkills } from '../useSkills';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useSkills', () => {
  it('fetches skills list', async () => {
    const { result } = renderHook(() => useSkills(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].name).toBe('Code Review');
    expect(result.current.data![0].category).toBe('CODE');
  });

  it('returns skill with correct properties', async () => {
    const { result } = renderHook(() => useSkills(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const skill = result.current.data![0];
    expect(skill.id).toBe('s1');
    expect(skill.builtin).toBe(true);
    expect(skill.capabilities).toHaveLength(2);
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useSkills(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});
