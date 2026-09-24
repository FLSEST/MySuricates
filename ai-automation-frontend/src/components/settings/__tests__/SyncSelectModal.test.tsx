import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { en } from '../../../i18n/en';
import type { SyncPreview } from '../../../types';
import { SyncSelectModal } from '../SyncSelectModal';

const preview: SyncPreview = {
  projects: [{ id: 'p1', name: 'Alpha', action: 'NEW' }],
  workflows: [
    { id: 'w1', name: 'Nightly', projectName: 'Alpha', action: 'UPDATE',
      diff: { changedFields: ['blocks', 'model'], modelBefore: 'sonnet', modelAfter: 'opus', blocksAdded: 2, blocksRemoved: 0 } },
    { id: 'w2', name: 'Loose', action: 'SKIP' },
  ],
  skills: [],
};

function renderModal() {
  const onSync = vi.fn().mockResolvedValue({});
  const onClose = vi.fn();
  render(
    <SyncSelectModal open onClose={onClose} preview={preview} previewLoading={false} onSync={onSync}
                     syncLoading={false} title="Sync" t={en} />,
  );
  return { onSync, onClose };
}

const checkbox = (name: string) => within(screen.getByText(name).closest('label')!).getByRole('checkbox');

describe('SyncSelectModal', () => {
  it('groups workflows by project, shows what changes and preselects the changes', () => {
    renderModal();
    expect(screen.getByText(`${en.settings.syncWorkflows} (2)`)).toBeInTheDocument();
    expect(screen.getByText(en.settings.syncNoProject)).toBeInTheDocument();
    expect(screen.getByText(`${en.settings.syncDiffBlocksAdded.replace('{n}', '2')} · model: sonnet → opus`)).toBeInTheDocument();
    expect(checkbox('Nightly')).toBeChecked();
    expect(checkbox('Loose')).not.toBeChecked();
    expect(screen.getByRole('button', { name: en.settings.syncItemCount.replace('{count}', '2') })).toBeEnabled();
  });

  it('collapses a section and selects everything at once', () => {
    renderModal();
    fireEvent.click(screen.getByText(`${en.settings.syncProjects} (1)`));
    expect(screen.queryByText('Alpha', { selector: 'span' })).toBeNull();

    fireEvent.click(checkbox(en.settings.syncSelectAll));
    expect(checkbox('Loose')).toBeChecked();
    expect(screen.getByRole('button', { name: en.settings.syncItemCount.replace('{count}', '3') })).toBeInTheDocument();
  });

  it('syncs the selection and closes', async () => {
    const { onSync, onClose } = renderModal();
    fireEvent.click(checkbox('Nightly'));
    fireEvent.click(screen.getByRole('button', { name: en.settings.syncItemCount.replace('{count}', '1') }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(onSync).toHaveBeenCalledWith({ projectIds: ['p1'], workflowIds: undefined, skillIds: undefined, chainIds: undefined });
  });
});
