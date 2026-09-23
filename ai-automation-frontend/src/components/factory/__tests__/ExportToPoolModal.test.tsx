import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ExportToPoolModal } from '../ExportToPoolModal';
import { usePoolExport } from '../../../hooks/usePool';
import { useFactoryTopics } from '../../../hooks/useWorkflowFactory';

vi.mock('../../../hooks/usePool', () => ({
  usePoolExport: vi.fn(),
}));

vi.mock('../../../hooks/useWorkflowFactory', () => ({
  useFactoryTopics: vi.fn(),
}));

const mockMutate = vi.fn();

describe('ExportToPoolModal', () => {
  beforeEach(() => {
    mockMutate.mockReset();
    vi.mocked(usePoolExport).mockReturnValue({
      mutate: mockMutate,
      isPending: false,
    } as unknown as ReturnType<typeof usePoolExport>);
    vi.mocked(useFactoryTopics).mockReturnValue({ data: [] } as unknown as ReturnType<typeof useFactoryTopics>);
  });

  function renderModal() {
    return render(
      <ExportToPoolModal
        open
        onClose={vi.fn()}
        workflowId="wf-1"
        workflowName="Test Workflow"
      />,
    );
  }

  it('defaults to scope portable', () => {
    renderModal();
    fireEvent.click(screen.getByRole('button', { name: /export/i }));
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({ scope: 'portable' }),
      expect.anything(),
    );
  });

  it('sends scope repo when the repo option is selected', () => {
    renderModal();
    fireEvent.click(screen.getByRole('radio', { name: /\(repo\)/i }));
    fireEvent.click(screen.getByRole('button', { name: /export/i }));
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({ scope: 'repo' }),
      expect.anything(),
    );
  });

  it('sends scope external for a workflow that needs a tool outside repo and project', () => {
    renderModal();
    fireEvent.click(screen.getByRole('radio', { name: /\(external\)/i }));
    fireEvent.click(screen.getByRole('button', { name: /export/i }));
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({ scope: 'external' }),
      expect.anything(),
    );
  });
});
