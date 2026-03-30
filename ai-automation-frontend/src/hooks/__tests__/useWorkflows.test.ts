import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useWorkflows } from '../useWorkflows';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useWorkflows', () => {
  it('fetches and maps workflows', async () => {
    const { result } = renderHook(() => useWorkflows(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const workflows = result.current.data!;
    expect(workflows).toHaveLength(1);
    expect(workflows[0].name).toBe('Test Workflow');
    expect(workflows[0].projectName).toBe('Test Project');
  });

  it('passes activatedSkillIds and activatedSkillNames from API response', async () => {
    const { result } = renderHook(() => useWorkflows(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const workflow = result.current.data![0];
    expect(workflow.activatedSkillIds).toEqual(['s1']);
    expect(workflow.activatedSkillNames).toEqual(['Code Review']);
  });

  it('defaults runCount to 0 if missing', async () => {
    const { result } = renderHook(() => useWorkflows(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data![0].runCount).toBe(5);
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useWorkflows(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});
