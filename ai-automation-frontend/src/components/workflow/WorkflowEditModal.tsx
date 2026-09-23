import { Input, Modal, ModalFooter } from '../ui';
import { useT } from '../../i18n';

export interface WorkflowEditForm {
  name: string;
  description: string;
  icon: string;
}

interface WorkflowEditModalProps {
  open: boolean;
  onClose: () => void;
  form: WorkflowEditForm;
  setForm: (v: WorkflowEditForm) => void;
  onSave: () => void;
  saving: boolean;
}

/** Name, description and icon of a workflow. */
export function WorkflowEditModal({ open, onClose, form, setForm, onSave, saving }: WorkflowEditModalProps) {
  const t = useT();
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.workflows.editWorkflow}
      footer={
        <ModalFooter
          onCancel={onClose}
          onAction={onSave}
          cancelLabel={t.common.cancel}
          actionLabel={t.common.save}
          actionLoading={saving}
        />
      }
    >
      <div className="space-y-4">
        <Input
          label={t.common.name}
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
        />
        <Input
          label={t.common.description}
          value={form.description}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
        />
        <Input
          label={t.workflows.iconLabel}
          value={form.icon}
          onChange={(e) => setForm({ ...form, icon: e.target.value })}
          placeholder="🚀"
        />
      </div>
    </Modal>
  );
}
