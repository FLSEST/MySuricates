import { useState } from 'react';
import { FolderPlus } from 'lucide-react';
import { Button, Input, Modal, Select } from '../ui';
import { TemplateSelector } from './TemplateSelector';
import type { CreateWorkflowRequest, Project, WorkflowTemplate } from '../../types';
import type { TranslationKeys } from '../../i18n';
import type { Locale } from '../../i18n';
import { resolveTemplateName } from '../../utils/templateUtils';
import { useCreateProject } from '../../hooks/useProjects';
import { FirstProjectActions } from '../project/FirstProjectActions';
import { ProjectFormFields } from '../project/ProjectFormFields';
import { EMPTY_PROJECT_FORM, type ProjectFormValues } from '../project/projectForm';

export type CreateStep = 'closed' | 'template' | 'details';

interface WorkflowCreateFlowProps {
  t: TranslationKeys;
  locale: Locale;
  createStep: CreateStep;
  selectedTemplate: WorkflowTemplate | null;
  form: CreateWorkflowRequest;
  projects: Project[] | undefined;
  createLoading: boolean;
  onFormChange: (form: CreateWorkflowRequest) => void;
  onTemplateSelect: (template: WorkflowTemplate | null) => void;
  onPoolSelect: (entry: import('../../types').PoolWorkflowEntry) => void;
  onCreate: () => void;
  onClose: () => void;
  onBackToTemplates: () => void;
}

export function WorkflowCreateFlow({
  t,
  locale,
  createStep,
  selectedTemplate,
  form,
  projects,
  createLoading,
  onFormChange,
  onTemplateSelect,
  onPoolSelect,
  onCreate,
  onClose,
  onBackToTemplates,
}: WorkflowCreateFlowProps) {
  // Non-null while the details dialog shows the project form instead (TODO d12c7560): the way from
  // "New workflow" to a project, inside the same dialog because a dialog cannot open a second one on top.
  const [newProject, setNewProject] = useState<ProjectFormValues | null>(null);
  const createProject = useCreateProject();
  const noProjects = projects !== undefined && projects.length === 0;

  const close = () => {
    setNewProject(null);
    onClose();
  };

  const handleCreateProject = () => {
    if (!newProject) return;
    createProject.mutate(newProject, {
      onSuccess: (project) => {
        setNewProject(null);
        onFormChange({ ...form, projectId: project.id });
      },
    });
  };

  return (
    <>
      <Modal open={createStep === 'template'} onClose={close} title={t.workflows.create} size="lg">
        <TemplateSelector onSelect={onTemplateSelect} onPoolSelect={onPoolSelect} />
      </Modal>

      <Modal
        open={createStep === 'details' && newProject !== null}
        onClose={close}
        title={t.projects.create}
        footer={
          <>
            <Button variant="secondary" onClick={() => setNewProject(null)}>
              {t.common.back}
            </Button>
            <Button
              onClick={handleCreateProject}
              loading={createProject.isPending}
              disabled={!newProject?.name || !newProject?.path}
            >
              {t.common.create}
            </Button>
          </>
        }
      >
        {newProject && <ProjectFormFields value={newProject} onChange={setNewProject} placeholders />}
      </Modal>

      <Modal
        open={createStep === 'details' && newProject === null}
        onClose={close}
        title={t.workflows.create}
        footer={
          <>
            <Button variant="secondary" onClick={onBackToTemplates}>
              {t.common.back}
            </Button>
            <Button
              onClick={onCreate}
              loading={createLoading}
              disabled={!form.name || !form.projectId}
            >
              {t.common.create}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          {selectedTemplate && (
            <div className="flex items-center gap-2 p-3 rounded-lg bg-accent/5 border border-accent/20">
              <span className="text-xl">{selectedTemplate.icon}</span>
              <div>
                <p className="text-sm font-medium text-text">
                  {resolveTemplateName(selectedTemplate.name, locale)}
                </p>
                <p className="text-xs text-text-secondary">
                  {selectedTemplate.blocks.length} {t.workflows.blocks.toLowerCase()}
                </p>
              </div>
            </div>
          )}
          {noProjects ? (
            <div className="space-y-3 p-3 rounded-lg border border-border bg-surface-raised text-center">
              <p className="text-sm text-text-secondary">{t.workflows.needsProject}</p>
              <FirstProjectActions
                onCreateOwn={() => setNewProject(EMPTY_PROJECT_FORM)}
                onProjectReady={(project) => onFormChange({ ...form, projectId: project.id })}
              />
            </div>
          ) : (
            <div>
              <Select
                label={t.common.project}
                value={form.projectId}
                onChange={(e) => onFormChange({ ...form, projectId: e.target.value })}
                options={[
                  { value: '', label: t.common.selectProject },
                  ...(projects?.map((p) => ({ value: p.id, label: p.name })) ?? []),
                ]}
              />
              <button
                type="button"
                onClick={() => setNewProject(EMPTY_PROJECT_FORM)}
                className="mt-1.5 inline-flex items-center gap-1 text-xs font-medium text-accent hover:text-accent-hover transition-colors"
              >
                <FolderPlus className="w-3.5 h-3.5" />
                {t.workflows.newProject}
              </button>
            </div>
          )}
          <Input
            label={t.common.name}
            value={form.name}
            onChange={(e) => onFormChange({ ...form, name: e.target.value })}
            placeholder="Review Pull Request"
          />
          <Input
            label={t.common.description}
            value={form.description ?? ''}
            onChange={(e) => onFormChange({ ...form, description: e.target.value })}
            placeholder="Optional description"
          />
        </div>
      </Modal>
    </>
  );
}
