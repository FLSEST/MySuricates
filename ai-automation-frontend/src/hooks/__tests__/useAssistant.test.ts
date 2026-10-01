import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useAssistantChat, useAssistantSuggestions } from '../useAssistant';
import { useI18nStore } from '../../i18n';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
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

  // 5157fac2: the English UI showed German suggestions – the hook asks for the UI language
  it('asks for the suggestions in the language of the UI', async () => {
    useI18nStore.setState({ locale: 'en' });
    let locale: string | null = null;
    server.use(
      http.get('/api/v1/assistant/suggestions', ({ request }) => {
        locale = new URL(request.url).searchParams.get('locale');
        return HttpResponse.json(['What ran today?']);
      }),
    );
    const { result } = renderHook(() => useAssistantSuggestions('dashboard'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(locale).toBe('en');
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
