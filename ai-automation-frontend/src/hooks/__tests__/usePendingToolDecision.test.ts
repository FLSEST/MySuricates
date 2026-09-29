import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { createTestQueryClient } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import { usePendingToolDecision } from '../usePendingToolDecision';
import type { PendingTool } from '../useAssistant';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function wrapper({ children }: { children: ReactNode }) {
  return createElement(QueryClientProvider, { client: createTestQueryClient() }, children);
}

function tool(expiresAt = ''): PendingTool {
  return { id: 'pt-1', type: 'start_workflow', description: 'Start', expiresAt };
}

const inTenMinutes = () => new Date(Date.now() + 600_000).toISOString();

describe('usePendingToolDecision', () => {
  it('keeps the answer of the confirm call as result and sends the session and UI language', async () => {
    useI18nStore.setState({ locale: 'de' });
    let body: unknown;
    server.use(http.post('/api/v1/assistant/pending-tools/:id/confirm', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ toolId: 'pt-1', type: 'start_workflow', success: true, message: 'Gestartet' });
    }));
    const { result } = renderHook(() => usePendingToolDecision(tool(inTenMinutes()), 's-1'), { wrapper });
    expect(result.current.result).toBeUndefined();
    expect(result.current.expired).toBe(false);

    await act(() => result.current.confirm());

    expect(result.current.result).toEqual({ toolId: 'pt-1', type: 'start_workflow', success: true, message: 'Gestartet' });
    expect(result.current.error).toBeNull();
    // language: the build check after a workflow tool answers in it (TODO 81773d7b)
    expect(body).toEqual({ sessionId: 's-1', language: 'de' });
  });

  it('keeps the answer of the reject call as result', async () => {
    server.use(http.post('/api/v1/assistant/pending-tools/:id/reject', () =>
      HttpResponse.json({ toolId: 'pt-1', type: 'start_workflow', success: false, message: 'Abgelehnt' })));
    const { result } = renderHook(() => usePendingToolDecision(tool(inTenMinutes())), { wrapper });

    await act(() => result.current.reject());

    expect(result.current.result?.message).toBe('Abgelehnt');
  });

  it('shows the server error next to the translated text and no result', async () => {
    useI18nStore.setState({ locale: 'en' });
    server.use(http.post('/api/v1/assistant/pending-tools/:id/confirm', () =>
      HttpResponse.json({ error: 'Proposal expired' }, { status: 410 })));
    const { result } = renderHook(() => usePendingToolDecision(tool(inTenMinutes())), { wrapper });

    await act(() => result.current.confirm());

    await waitFor(() => expect(result.current.error).toBe(`${en.assistant.pendingToolError}: Proposal expired`));
    expect(result.current.result).toBeUndefined();
  });

  it('falls back to the translated error without a server message', async () => {
    useI18nStore.setState({ locale: 'en' });
    server.use(http.post('/api/v1/assistant/pending-tools/:id/confirm', () => HttpResponse.error()));
    const { result } = renderHook(() => usePendingToolDecision(tool(inTenMinutes())), { wrapper });

    await act(() => result.current.confirm());

    expect(result.current.error).toBe(en.assistant.pendingToolError);
  });

  it('starts with an existing result and marks an expired proposal', () => {
    const past = new Date(Date.now() - 1000).toISOString();
    const existing = { toolId: 'pt-1', type: 'start_workflow', success: true, message: 'Schon erledigt' };
    const { result } = renderHook(() => usePendingToolDecision(tool(past), undefined, existing), { wrapper });
    expect(result.current.result).toEqual(existing);
    expect(result.current.expired).toBe(true);
  });

  it('treats a proposal without expiry as not expired', () => {
    const { result } = renderHook(() => usePendingToolDecision(tool()), { wrapper });
    expect(result.current.expired).toBe(false);
    expect(result.current.pending).toBe(false);
  });
});
