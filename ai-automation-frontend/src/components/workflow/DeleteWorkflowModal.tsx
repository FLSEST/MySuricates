import { Modal, ModalFooter } from '../ui';
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
    <Modal
      open={open}
      onClose={onClose}
      title={`${t.common.delete} "${workflowName}"?`}
      size="sm"
      footer={
        <ModalFooter
          onCancel={onClose}
          onAction={onDelete}
          cancelLabel={t.common.cancel}
          actionLabel={t.common.delete}
          actionVariant="danger"
          actionLoading={deleting}
        />
      }
    >
      <p className="text-sm text-text-secondary">
        {t.common.deleteConfirm} {t.workflows.deleteConfirm}
      </p>
    </Modal>
  );
}
