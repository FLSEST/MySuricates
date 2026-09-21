import { describe, it, expect, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { HumanInputDialog } from '../HumanInputDialog';

beforeEach(() => useI18nStore.setState({ locale: 'en' }));

describe('HumanInputDialog', () => {
  it('renders the prompt as Markdown in a scrollable area', () => {
    renderWithProviders(
      <HumanInputDialog
        humanInput={{
          blockId: 'human-input-2', prompt: '## Findings\n\nWhich file goes to the **archive**?', inputType: 'text',
          options: [], defaultValue: '', required: true, validationPattern: null, variableName: 'file', stepIndex: 2,
        }}
        onSubmit={() => {}}
      />,
    );

    expect(screen.getByRole('heading', { name: 'Findings' })).toBeInTheDocument();
    expect(screen.getByText('archive').tagName).toBe('STRONG');
    expect(screen.getByTestId('approval-question').className).toContain('overflow-y-auto');
  });
});
