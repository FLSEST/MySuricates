import { ConfirmModal } from '../ui';
import { useT } from '../../i18n';

interface DeleteWorkflowModalProps {
  open: boolean;
  onClose: () => void;
  workflowName: string;
  onDelete: () => void;
  deleting: boolean;
}

export function DeleteWorkflowModal({ open, onClose, workflowName, onDelete, deleting }: DeleteWorkflowModalProps) {
  const t = useT();
  return (
    <ConfirmModal
      open={open}
      onClose={onClose}
      onConfirm={onDelete}
      title={`${t.common.delete} "${workflowName}"?`}
      message={<>{t.common.deleteConfirm} {t.workflows.deleteConfirm}</>}
      confirmLabel={t.common.delete}
      variant="danger"
      isPending={deleting}
    />
  );
}
