import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { waitFor } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { server } from '../../test/server';
import { useAssistantStore } from '../../stores/assistantStore';
import { useI18nStore } from '../../i18n';
import { de } from '../../i18n/de';
import { en } from '../../i18n/en';
import { cancelAssistantMessage, resetAssistantConversation, sendAssistantMessage } from '../useAssistantConversation';
import type { AssistantChatRequest } from '../useAssistant';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => { server.resetHandlers(); resetAssistantConversation(); });
afterAll(() => server.close());
beforeEach(() => useAssistantStore.setState({ sessions: [], activeSessionId: null, inFlight: null, lastModel: null }));

const params = () => ({ context: { page: 'dashboard', pathname: '/' }, language: 'en', queryClient: new QueryClient() });
const messages = (sessionId: string) => useAssistantStore.getState().sessions.find((s) => s.id === sessionId)!.messages;

function answering(requests: AssistantChatRequest[], wait = 0) {
  server.use(http.post('/api/v1/assistant/chat', async ({ request }) => {
    const req = (await request.json()) as AssistantChatRequest;
    requests.push(req);
    if (wait) await delay(wait);
    return HttpResponse.json({ message: `Re: ${req.message}`, actions: [], model: 'sonnet' });
  }));
}

describe('assistant conversation', () => {
  it('sends a turn and stores the answer as the reply to it', async () => {
    const requests: AssistantChatRequest[] = [];
    answering(requests);
    const sessionId = useAssistantStore.getState().startNewSession();

    const userMsgId = sendAssistantMessage('Hi', sessionId, params());
    expect(useAssistantStore.getState().inFlight).toMatchObject({ sessionId, userMsgId });

    await waitFor(() => expect(messages(sessionId)).toHaveLength(2));
    expect(messages(sessionId)[0]).toMatchObject({ id: userMsgId, status: 'sent' });
    expect(messages(sessionId)[1]).toMatchObject({ role: 'assistant', content: 'Re: Hi', replyTo: userMsgId });
    expect(useAssistantStore.getState()).toMatchObject({ inFlight: null, lastModel: 'sonnet' });
    expect(requests[0]).toMatchObject({ message: 'Hi', history: [], language: 'en', sessionId });
  });

  it('queues a second turn and sends it with the first exchange as history, once', async () => {
    const requests: AssistantChatRequest[] = [];
    answering(requests, 30);
    const sessionId = useAssistantStore.getState().startNewSession();

    sendAssistantMessage('One', sessionId, params());
    const second = sendAssistantMessage('Two', sessionId, params());

    await waitFor(() => expect(messages(sessionId)).toHaveLength(4));
    expect(requests.map((r) => r.message)).toEqual(['One', 'Two']);
    expect(requests[1]!.history).toEqual([{ role: 'user', content: 'One' }, { role: 'assistant', content: 'Re: One' }]);
    // The queued message is not added a second time when it goes out
    expect(messages(sessionId).filter((m) => m.content === 'Two')).toHaveLength(1);
    expect(messages(sessionId).find((m) => m.replyTo === second)?.content).toBe('Re: Two');
  });

  it('cancels without an error bubble and takes a queued turn out', async () => {
    const requests: AssistantChatRequest[] = [];
    answering(requests, 5_000);
    const sessionId = useAssistantStore.getState().startNewSession();

    const first = sendAssistantMessage('One', sessionId, params());
    const second = sendAssistantMessage('Two', sessionId, params());
    cancelAssistantMessage(second);
    expect(messages(sessionId).find((m) => m.id === second)?.status).toBeUndefined();

    cancelAssistantMessage(first);
    expect(useAssistantStore.getState().inFlight).toBeNull();
    await waitFor(() => expect(messages(sessionId).find((m) => m.id === first)?.status).toBeUndefined());
    expect(messages(sessionId).some((m) => m.role === 'assistant')).toBe(false);
  });

  it('answers a failed request with an error message', async () => {
    server.use(http.post('/api/v1/assistant/chat', () => HttpResponse.json({ error: 'boom' }, { status: 500 })));
    const sessionId = useAssistantStore.getState().startNewSession();

    const userMsgId = sendAssistantMessage('Hi', sessionId, params());

    await waitFor(() => expect(messages(sessionId)).toHaveLength(2));
    expect(messages(sessionId)[0]).toMatchObject({ status: 'error' });
    expect(messages(sessionId)[1]).toMatchObject({ isError: true, replyTo: userMsgId });
  });

  it.each([['en', en], ['de', de]] as const)('writes the error message in the UI language (%s)', async (locale, texts) => {
    const before = useI18nStore.getState().locale;
    useI18nStore.setState({ locale });
    try {
      server.use(http.post('/api/v1/assistant/chat', () => HttpResponse.json({ error: 'boom' }, { status: 500 })));
      const sessionId = useAssistantStore.getState().startNewSession();

      sendAssistantMessage('Hi', sessionId, params());

      await waitFor(() => expect(messages(sessionId)).toHaveLength(2));
      expect(messages(sessionId)[1].content).toBe(texts.assistant.errorReply);
    } finally {
      useI18nStore.setState({ locale: before });
    }
  });
});
