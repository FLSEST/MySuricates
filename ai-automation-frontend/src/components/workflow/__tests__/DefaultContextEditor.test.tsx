import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';

const mutate = vi.fn();
vi.mock('../../../hooks/useWorkflows', () => ({
  useUpdateWorkflow: () => ({ mutate, isPending: false }),
}));

import { DefaultContextEditor } from '../DefaultContextEditor';

beforeEach(() => {
  mutate.mockClear();
  useI18nStore.setState({ locale: 'en' });
});

describe('DefaultContextEditor', () => {
  it('offers saving only after a change and saves the filled-in values', () => {
    render(<DefaultContextEditor workflowId="wf-1" variables={['topic', 'lang']} defaultContext={{ lang: 'de' }} />);
    expect(screen.getByLabelText('lang')).toHaveValue('de');
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('topic'), { target: { value: 'Release' } });
    fireEvent.change(screen.getByLabelText('lang'), { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    // Blank values are left out, so the run falls back to its own default
    expect(mutate).toHaveBeenCalledWith({ id: 'wf-1', data: { defaultContext: { topic: 'Release' } } });
  });

  it('takes over new values from the server', () => {
    const { rerender } = render(
      <DefaultContextEditor workflowId="wf-1" variables={['topic']} defaultContext={{ topic: 'A' }} />,
    );
    fireEvent.change(screen.getByLabelText('topic'), { target: { value: 'local edit' } });

    rerender(<DefaultContextEditor workflowId="wf-1" variables={['topic']} defaultContext={{ topic: 'B' }} />);
    expect(screen.getByLabelText('topic')).toHaveValue('B');
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
  });
});
