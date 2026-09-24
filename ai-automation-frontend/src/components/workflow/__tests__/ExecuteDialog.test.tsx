import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import { ExecuteDialog } from '../ExecuteDialog';

beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
});

function renderDialog(props: Partial<React.ComponentProps<typeof ExecuteDialog>> = {}) {
  const onExecute = vi.fn();
  render(
    <ExecuteDialog
      open
      onClose={() => {}}
      onExecute={onExecute}
      variables={['project_name', 'max_items']}
      optionalVariables={['max_items']}
      {...props}
    />,
  );
  return onExecute;
}

describe('ExecuteDialog', () => {
  it('shows the raw variable names by default (Pro)', () => {
    renderDialog();
    expect(screen.getByText(en.workflows.executeWithVariables)).toBeInTheDocument();
    expect(screen.getByText('project_name')).toBeInTheDocument();
    expect(screen.getByText(`max_items (${en.workflows.optional})`)).toBeInTheDocument();
    expect(screen.getByPlaceholderText(`${en.workflows.enterValue} project_name`)).toBeInTheDocument();
  });

  it('reads names as words with humanizeLabels and still sends the raw keys', () => {
    const onExecute = renderDialog({ humanizeLabels: true, title: 'Details for this run' });

    expect(screen.getByText('Details for this run')).toBeInTheDocument();
    expect(screen.queryByText(en.workflows.executeWithVariables)).not.toBeInTheDocument();
    expect(screen.getByText('Project name')).toBeInTheDocument();
    expect(screen.getByText(`Max items (${en.workflows.optional})`)).toBeInTheDocument();
    expect(screen.queryByText('project_name')).not.toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText(`${en.workflows.enterValue} Project name`), { target: { value: 'Atlas' } });
    fireEvent.click(screen.getByRole('button', { name: en.workflows.execute }));
    expect(onExecute).toHaveBeenCalledWith({ project_name: 'Atlas', max_items: '' });
  });

  it('keeps descriptions and custom placeholders with humanizeLabels', () => {
    renderDialog({
      humanizeLabels: true,
      descriptions: { project_name: 'Which project the report covers' },
      placeholders: { project_name: 'e.g. Atlas' },
    });
    expect(screen.getByText('Which project the report covers')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('e.g. Atlas')).toBeInTheDocument();
  });
});
