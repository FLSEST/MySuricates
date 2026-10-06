import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';

const mutate = vi.fn();
const navigate = vi.fn();
const toastSuccess = vi.fn();
const toastError = vi.fn();

vi.mock('react-router-dom', () => ({ useNavigate: () => navigate }));
vi.mock('../../../hooks/useProjects', () => ({ useProjects: () => ({ data: [{ id: 'p1', name: 'Projekt 1' }] }) }));
vi.mock('../../../hooks/useWorkflows', () => ({ useImportWorkflowBpmn: () => ({ mutate, isPending: false }) }));
vi.mock('../../../hooks/useToast', () => ({ useToast: () => ({ success: toastSuccess, error: toastError }) }));

import { BpmnImportButton } from '../BpmnImportButton';

beforeEach(() => {
  mutate.mockReset();
  navigate.mockReset();
  toastSuccess.mockReset();
  toastError.mockReset();
  useI18nStore.setState({ locale: 'de' });
});

describe('BpmnImportButton', () => {
  it('imports directly when there is only one project and navigates to the new workflow', async () => {
    mutate.mockImplementation((_vars, opts) => opts.onSuccess({ id: 'new-42' }));
    const { container } = render(<BpmnImportButton />);
    expect(screen.getByText('BPMN importieren')).toBeInTheDocument();

    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['<definitions/>'], 'wf.bpmn', { type: 'application/xml' });
    // jsdom File.text() returns the content
    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() => expect(mutate).toHaveBeenCalled());
    expect(mutate.mock.calls[0][0]).toEqual({ xml: '<definitions/>', projectId: 'p1' });
    expect(navigate).toHaveBeenCalledWith('/workflows/new-42');
    expect(toastSuccess).toHaveBeenCalledWith('Workflow aus BPMN importiert');
  });

  it('shows the backend message for BPMN without extension (400) verbatim in the toast and does not navigate', async () => {
    mutate.mockImplementation((_vars, opts) =>
      opts.onError({ code: 'INVALID_ARGUMENT', error: 'Diese BPMN enthält keine mysuricates:blockConfig-Erweiterung' }),
    );
    const { container } = render(<BpmnImportButton />);
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['<definitions/>'], 'fremd.bpmn')] } });

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(toastError.mock.calls[0][0]).toBe('BPMN-Import fehlgeschlagen: Diese BPMN enthält keine mysuricates:blockConfig-Erweiterung');
    expect(navigate).not.toHaveBeenCalled();
  });
});
