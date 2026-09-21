import { useState } from 'react';
import { Button, Modal } from '../ui';
import { useCreateProject } from '../../hooks/useProjects';
import { useT } from '../../i18n';
import type { Project } from '../../types';
import { ProjectFormFields } from './ProjectFormFields';
import { EMPTY_PROJECT_FORM, type ProjectFormValues } from './projectForm';

/** "New project" as a dialog; the form starts empty every time it opens. */
export function CreateProjectModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated?: (project: Project) => void;
}) {
  const t = useT();
  const createProject = useCreateProject();
  const [form, setForm] = useState<ProjectFormValues>(EMPTY_PROJECT_FORM);

  const close = () => {
    setForm(EMPTY_PROJECT_FORM);
    onClose();
  };

  const handleCreate = () => {
    createProject.mutate(form, {
      onSuccess: (project) => {
        close();
        onCreated?.(project);
      },
    });
  };

  return (
    <Modal
      open={open}
      onClose={close}
      title={t.projects.create}
      footer={
        <>
          <Button variant="secondary" onClick={close}>
            {t.common.cancel}
          </Button>
          <Button onClick={handleCreate} loading={createProject.isPending} disabled={!form.name || !form.path}>
            {t.common.create}
          </Button>
        </>
      }
    >
      <ProjectFormFields value={form} onChange={setForm} placeholders />
    </Modal>
  );
}
