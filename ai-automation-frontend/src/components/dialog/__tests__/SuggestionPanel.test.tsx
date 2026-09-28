import { describe, it, expect, beforeEach, vi } from 'vitest';
import { createElement } from 'react';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import { useToastStore } from '../../../stores/toastStore';
import { SuggestionPanel } from '../SuggestionPanel';
import type { DialogWorkflowSuggestion } from '../../../types';

// jsdom can't run Monaco
vi.mock('../../shared/MonacoEditor', () => ({
  MonacoEditor: ({ value, onChange }: { value: string; onChange?: (v: string) => void }) =>
    createElement('textarea', {
      'data-testid': 'mock-monaco',
      value,
      onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => onChange?.(e.target.value),
    }),
}));

beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  useToastStore.setState({ toasts: [] });
});

const suggestion = {
  suggestedName: 'Fix Login Bug',
  suggestedDescription: 'Patches the auth logic',
  blocks: [
    { type: 'shell', config: { command: 'npm test' } },
    { type: 'simple', config: { prompt: 'Review changes' } },
  ],
  confidence: 0.6,
  patternType: 'scripting',
  notAvailableReason: null,
  toolCallsAnalyzed: 8,
  available: true,
} as unknown as DialogWorkflowSuggestion;

const projects = [{ id: 'p1', name: 'Shop' }];

function renderPanel(overrides: Partial<DialogWorkflowSuggestion> = {}, onCreate = vi.fn()) {
  renderWithProviders(
    <SuggestionPanel
      suggestion={{ ...suggestion, ...overrides }}
      projects={projects}
      onCreateWorkflow={onCreate}
      isCreating={false}
    />,
  );
  return onCreate;
}

describe('SuggestionPanel', () => {
  it('shows only the reason when no workflow can be suggested', () => {
    renderPanel({ available: false, notAvailableReason: 'Too few tool calls' });
    expect(screen.getByText('Too few tool calls')).toBeInTheDocument();
    expect(screen.queryByText(en.dialog.createWorkflow)).toBeNull();
  });

  it('warns about a low confidence', () => {
    renderPanel();
    expect(screen.getByText('60%')).toBeInTheDocument();
    expect(screen.getByText(en.dialog.lowConfidenceWarning)).toBeInTheDocument();
  });

  it('creates only with project and name, and hands over the edited blocks', () => {
    const onCreate = renderPanel();
    const create = screen.getByRole('button', { name: en.dialog.createWorkflow });
    expect(create).toBeDisabled();

    fireEvent.click(screen.getByText('npm test'));
    fireEvent.change(screen.getByTestId('mock-monaco'), { target: { value: '{"command":"npm run e2e"}' } });
    fireEvent.click(screen.getByRole('button', { name: en.common.save }));
    expect(screen.getByText('npm run e2e')).toBeInTheDocument();

    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'p1' } });
    expect(create).toBeEnabled();
    fireEvent.click(create);
    expect(onCreate).toHaveBeenCalledWith('p1', 'Fix Login Bug', [
      { type: 'shell', config: { command: 'npm run e2e' } },
      suggestion.blocks[1],
    ]);
  });

  it('keeps the editor open and reports invalid JSON', () => {
    renderPanel();
    fireEvent.click(screen.getByText('npm test'));
    fireEvent.change(screen.getByTestId('mock-monaco'), { target: { value: '{ broken' } });
    fireEvent.click(screen.getByRole('button', { name: en.common.save }));

    expect(screen.getByTestId('mock-monaco')).toBeInTheDocument();
    expect(useToastStore.getState().toasts.map((t) => t.message)).toEqual([en.dialog.invalidJson]);
  });
});
