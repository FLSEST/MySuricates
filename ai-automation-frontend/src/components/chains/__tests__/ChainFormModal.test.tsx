import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ChainFormModal } from '../ChainFormModal';
import type { WorkflowChain } from '../../../types';

const chain: WorkflowChain = {
  id: 'c-1', name: 'TODO-Batch', description: null, icon: null, stopOnFailure: true, runCount: 0,
  metadata: {}, createdAt: '2026-09-25T00:00:00Z', updatedAt: '2026-09-25T00:00:00Z',
  entries: [{ workflowId: 'wf-1', workflowName: 'A', projectName: 'P', label: 'Einordnen', sortOrder: 0,
              stopOnFailure: true, inputContext: { batch_max: '6' }, timeoutMinutes: 1200 }],
};

describe('ChainFormModal', () => {
  it('keeps inputContext and timeoutMinutes of existing entries when saving (46e394e0)', () => {
    const onSave = vi.fn();
    render(<ChainFormModal title="Kette" chain={chain} workflows={[]} onSave={onSave} onClose={vi.fn()} saving={false} />);

    fireEvent.click(screen.getByRole('button', { name: /speichern|save/i }));

    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      entries: [expect.objectContaining({ inputContext: { batch_max: '6' }, timeoutMinutes: 1200 })],
    }));
  });
});
