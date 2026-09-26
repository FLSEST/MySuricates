import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { en } from '../../../i18n/en';
import type { ChatMessage } from '../../../hooks/useAssistant';
import { MessageBubble } from '../AssistantMessage';

const userMsg: ChatMessage = { id: 'u1', role: 'user', content: 'Hello', timestamp: Date.now() };
const errorMsg: ChatMessage = {
  id: 'a1', role: 'assistant', content: 'failed', timestamp: Date.now(), isError: true, replyTo: 'u1',
};

afterEach(() => useI18nStore.setState({ locale: 'en' }));

// The avatar and the retry button carried German literals into the English UI.
describe('MessageBubble texts follow the UI language', { timeout: 15_000 }, () => {
  it.each([['en', en], ['de', de]] as const)('user avatar and retry button (%s)', (locale, texts) => {
    useI18nStore.setState({ locale });
    const onRetry = vi.fn();
    renderWithProviders(
      <>
        <MessageBubble msg={userMsg} onClose={vi.fn()} />
        <MessageBubble msg={errorMsg} onClose={vi.fn()} onRetry={onRetry} />
      </>,
    );

    expect(screen.getByText(texts.assistant.userAvatar)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: texts.common.retry }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('shows no German in the English bubble', () => {
    renderWithProviders(
      <>
        <MessageBubble msg={userMsg} onClose={vi.fn()} />
        <MessageBubble msg={errorMsg} onClose={vi.fn()} onRetry={vi.fn()} />
      </>,
    );
    expect(screen.queryByText(de.assistant.userAvatar)).not.toBeInTheDocument();
    expect(screen.queryByText(de.common.retry)).not.toBeInTheDocument();
  });
});
