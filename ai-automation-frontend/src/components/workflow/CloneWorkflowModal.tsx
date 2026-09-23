import { Button, Input, Select, Modal } from '../ui';
import { useT } from '../../i18n';
import type { Project } from '../../types';

export interface CloneForm {
  projectId: string;
  name: string;
}

interface CloneWorkflowModalProps {
  open: boolean;
  onClose: () => void;
  form: CloneForm;
  setForm: (v: CloneForm) => void;
  projects: Project[] | undefined;
  onClone: () => void;
  cloning: boolean;
}

/** Copies the workflow into a project of choice under a new name. */
export function CloneWorkflowModal({ open, onClose, form, setForm, projects, onClone, cloning }: CloneWorkflowModalProps) {
  const t = useT();
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.workflows.cloneWorkflow}
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>{t.common.cancel}</Button>
          <Button
            onClick={onClone}
            loading={cloning}
            disabled={!form.projectId || !form.name.trim()}
          >
            {t.common.create}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Select
          label={t.workflows.cloneProject}
          value={form.projectId}
          onChange={(e) => setForm({ ...form, projectId: e.target.value })}
          options={[
            { value: '', label: t.common.selectProject },
            ...(projects?.map((p) => ({ value: p.id, label: p.name })) ?? []),
          ]}
        />
        <Input
          label={t.workflows.cloneName}
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
        />
      </div>
    </Modal>
  );
}
