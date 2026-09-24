import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { useAssistantStore } from '../../stores/assistantStore';
import { AssistantPanel } from '../AssistantPanel';

// No live connection in the test: the panel only needs the hook's shape.
vi.mock('../../hooks/useAssistantWebSocket', () => ({
  useAssistantWebSocket: () => ({ currentPhase: null, events: [] }),
}));

beforeAll(() => {
  // jsdom lacks scrollIntoView; the panel scrolls its message list with it.
  Element.prototype.scrollIntoView = vi.fn();
});

afterEach(() => {
  useI18nStore.setState({ locale: 'en' });
  useAssistantStore.setState({ open: false });
});

// Input placeholder, toggle, history and resize handles stood in German in the English UI too
// (e86f6544, found by the e2e run).
describe('AssistantPanel texts', () => {
  it('speak English in the English UI', () => {
    useI18nStore.setState({ locale: 'en' });
    useAssistantStore.setState({ open: true, showHistory: false });
    renderWithProviders(<AssistantPanel />);

    expect(screen.getByPlaceholderText('Type a message… (Enter to send)')).toBeInTheDocument();
    expect(screen.getByTitle('MySuricates assistant')).toBeInTheDocument();
    expect(screen.getByTitle('Conversation history')).toBeInTheDocument();
    expect(screen.getByTitle('Adjust width')).toBeInTheDocument();
    expect(screen.getByTitle('Adjust height')).toBeInTheDocument();
  });

  it('speak German in the German UI', () => {
    useI18nStore.setState({ locale: 'de' });
    useAssistantStore.setState({ open: true, showHistory: false });
    renderWithProviders(<AssistantPanel />);

    expect(screen.getByPlaceholderText('Nachricht eingeben… (Enter zum Senden)')).toBeInTheDocument();
    expect(screen.getByTitle('MySuricates Assistent')).toBeInTheDocument();
    expect(screen.getByTitle('Gesprächsverlauf')).toBeInTheDocument();
  });
});
