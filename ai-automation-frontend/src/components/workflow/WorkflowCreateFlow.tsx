import { Button, Input, Modal, Select } from '../ui';
import { TemplateSelector } from './TemplateSelector';
import type { CreateWorkflowRequest, Project, WorkflowTemplate } from '../../types';
import type { TranslationKeys } from '../../i18n';
import type { Locale } from '../../i18n';
import { resolveTemplateName } from '../../utils/templateUtils';

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
  return (
    <>
      <Modal open={createStep === 'template'} onClose={onClose} title={t.workflows.create} size="lg">
        <TemplateSelector onSelect={onTemplateSelect} onPoolSelect={onPoolSelect} />
      </Modal>

      <Modal
        open={createStep === 'details'}
        onClose={onClose}
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
          <Select
            label={t.common.project}
            value={form.projectId}
            onChange={(e) => onFormChange({ ...form, projectId: e.target.value })}
            options={[
              { value: '', label: t.common.selectProject },
              ...(projects?.map((p) => ({ value: p.id, label: p.name })) ?? []),
            ]}
          />
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
