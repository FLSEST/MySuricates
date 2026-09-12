import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useWebhooks, useCreateWebhook, useDeleteWebhook, useRegenerateWebhookToken } from '../useWebhooks';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useWebhooks', () => {
  it('fetches webhooks successfully', async () => {
    const { result } = renderHook(() => useWebhooks(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].name).toBe('Test Webhook');
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useWebhooks(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useCreateWebhook', () => {
  it('creates a webhook via mutation', async () => {
    const { result } = renderHook(() => useCreateWebhook(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ workflowId: 'w1', name: 'New Webhook' } as any);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('wh2');
  });
});

describe('useDeleteWebhook', () => {
  it('deletes a webhook via mutation', async () => {
    const { result } = renderHook(() => useDeleteWebhook(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('wh1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useRegenerateWebhookToken', () => {
  it('regenerates a webhook token and reveals it in full', async () => {
    const { result } = renderHook(() => useRegenerateWebhookToken(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('wh1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.tokenVisible).toBe(true);
    expect(result.current.data!.secretToken).toBe('zzz999NEWTOKENvalue1234567890ABCDEF');
  });
});
