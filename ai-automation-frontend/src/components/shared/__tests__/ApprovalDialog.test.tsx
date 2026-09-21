import { describe, it, expect, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { ApprovalDialog } from '../ApprovalDialog';

beforeEach(() => useI18nStore.setState({ locale: 'en' }));

describe('ApprovalDialog', () => {
  it('renders the question as Markdown in a scrollable area', () => {
    renderWithProviders(
      <ApprovalDialog
        approval={{ blockId: 'approval-3', question: '## Measurement\n\n- 12 images', options: ['Ja', 'Nein'], rejectOptions: ['Nein'], stepIndex: 3 }}
        onSubmit={() => {}}
      />,
    );

    expect(screen.getByRole('heading', { name: 'Measurement' })).toBeInTheDocument();
    expect(screen.getByRole('listitem')).toHaveTextContent('12 images');
    expect(screen.getByTestId('approval-question').className).toContain('overflow-y-auto');
  });

  it('marks the options the backend names as rejections', () => {
    renderWithProviders(
      <ApprovalDialog
        approval={{ blockId: 'approval-3', question: 'Adopt?', options: ['Ja', 'Nein'], rejectOptions: ['Nein'], stepIndex: 3 }}
        onSubmit={() => {}}
      />,
    );

    expect(screen.getByRole('button', { name: 'Nein' }).className).toContain('bg-danger');
    expect(screen.getByRole('button', { name: 'Ja' }).className).not.toContain('bg-danger');
  });

  it('falls back to "No" when an older backend sends no reject list', () => {
    renderWithProviders(
      <ApprovalDialog
        approval={{ blockId: 'approval-3', question: 'Deploy?', options: ['Yes', 'No'], stepIndex: 3 }}
        onSubmit={() => {}}
      />,
    );

    expect(screen.getByRole('button', { name: 'No' }).className).toContain('bg-danger');
  });
});
