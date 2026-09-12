import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useAssistantChat, useAssistantSuggestions } from '../useAssistant';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useAssistantSuggestions', () => {
  it('fetches suggestions for a page', async () => {
    server.use(
      http.get('/api/v1/assistant/suggestions', () =>
        HttpResponse.json(['How can I help?', 'Show workflows'])),
    );
    const { result } = renderHook(() => useAssistantSuggestions('dashboard'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(2);
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/assistant/suggestions', () =>
        HttpResponse.json([])),
    );
    const { result } = renderHook(() => useAssistantSuggestions('dashboard'), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useAssistantChat', () => {
  it('sends a chat message', async () => {
    server.use(
      http.post('/api/v1/assistant/chat', () =>
        HttpResponse.json({ message: 'Hello!', actions: [], model: 'sonnet' })),
    );
    const { result } = renderHook(() => useAssistantChat(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ req: { message: 'Hi', history: [] } });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.message).toBe('Hello!');
  });
});
