import { Trash2 } from 'lucide-react';
import { ConfirmModal } from '../ui';
import type { TranslationKeys } from '../../i18n';
import type { WorkflowDeleteConfirm } from '../../hooks/useWorkflowBulkSelection';

/** Bestätigung vor dem Löschen eines oder mehrerer Workflows aus der Liste, mit ihren Namen. */
export function WorkflowBulkDeleteModal({ t, confirm, deleting, onClose, onConfirm }: {
  t: TranslationKeys;
  confirm: WorkflowDeleteConfirm;
  deleting: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  return (
    <ConfirmModal
      open={confirm.open}
      onClose={onClose}
      onConfirm={onConfirm}
      title={confirm.ids.length === 1 ? t.workflows.deleteWorkflow : t.workflows.deleteWorkflows}
      confirmLabel={<>{t.common.delete} ({confirm.ids.length})</>}
      confirmIcon={<Trash2 className="w-4 h-4" />}
      variant="danger"
      isPending={deleting}
      cancelDisabled={deleting}
    >
      <div className="space-y-3">
        <p className="text-sm text-text-secondary">{t.workflows.deleteConfirm}</p>
        <div className="max-h-48 overflow-y-auto space-y-1">
          {confirm.names.map((name, i) => (
            <div key={i} className="flex items-center gap-2 px-2 py-1 rounded-sm bg-surface-raised text-sm text-text">
              <Trash2 className="w-3 h-3 text-danger shrink-0" />
              <span className="truncate">{name}</span>
            </div>
          ))}
        </div>
        <p className="text-xs text-danger">{t.common.deleteConfirm}</p>
      </div>
    </ConfirmModal>
  );
}
