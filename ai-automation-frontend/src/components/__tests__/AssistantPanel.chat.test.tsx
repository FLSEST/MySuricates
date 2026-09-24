import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import { useAssistantStore } from '../../stores/assistantStore';
import { resetAssistantConversation } from '../../hooks/useAssistantConversation';
import type { AssistantChatRequest } from '../../hooks/useAssistant';
import { AssistantPanel } from '../AssistantPanel';

vi.mock('../../hooks/useAssistantWebSocket', () => ({
  useAssistantWebSocket: () => ({ currentPhase: null, events: [] }),
}));

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'bypass' });
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => { server.resetHandlers(); resetAssistantConversation(); });
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  useAssistantStore.setState({ open: true, showHistory: false, sessions: [], activeSessionId: null, pendingContext: null });
});

const input = () => screen.getByPlaceholderText(en.assistant.inputPlaceholder);
const say = (text: string) => {
  fireEvent.change(input(), { target: { value: text } });
  fireEvent.keyDown(input(), { key: 'Enter' });
};

describe('AssistantPanel — chatting', () => {
  it('sends the message with the page context and shows the answer', async () => {
    const requests: AssistantChatRequest[] = [];
    server.use(http.post('/api/v1/assistant/chat', async ({ request }) => {
      requests.push((await request.json()) as AssistantChatRequest);
      return HttpResponse.json({ message: 'Here you go', actions: [], model: 'claude-sonnet' });
    }));
    renderWithProviders(<AssistantPanel />, { route: '/workflows/w1' });

    say('Why did it fail?');

    expect(await screen.findByText('Here you go')).toBeInTheDocument();
    expect(screen.getByText('Why did it fail?')).toBeInTheDocument();
    expect(requests[0]).toMatchObject({ message: 'Why did it fail?', history: [], language: 'en', context: { page: 'workflow', workflowId: 'w1' } });
    expect(screen.getByText('sonnet')).toBeInTheDocument();
  });

  it('cancels a pending answer', async () => {
    server.use(http.post('/api/v1/assistant/chat', async () => {
      await delay(5_000);
      return HttpResponse.json({ message: 'late', actions: [] });
    }));
    renderWithProviders(<AssistantPanel />);

    say('Slow one');
    fireEvent.click(await screen.findByRole('button', { name: en.common.cancel }));

    await waitFor(() => expect(screen.queryByRole('button', { name: en.common.cancel })).toBeNull());
    expect(useAssistantStore.getState().inFlight).toBeNull();
  });
});
