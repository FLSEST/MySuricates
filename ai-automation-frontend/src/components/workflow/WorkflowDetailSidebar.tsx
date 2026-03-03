import { useState } from 'react';
import { Plus, Pencil, Zap, Clock, FileText, Bot, Trash2 } from 'lucide-react';
import { Card, CardHeader, Button, Input, Select } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { WorkflowMemoryPanel } from './WorkflowMemoryPanel';
import { useUpdateWorkflow } from '../../hooks/useWorkflows';
import { useT } from '../../i18n';
import { formatDuration } from '../../utils/formatTime';
import type { Workflow, WorkflowRun, PageResponse } from '../../types';

const MODEL_OPTIONS = [
  { value: '', label: '' },
  { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6 (fast)' },
  { value: 'claude-opus-4-6', label: 'Claude Opus 4.6 (powerful)' },
  { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5 (fastest)' },
];

const PROVIDER_OPTIONS = [
  { value: '', label: '' },
  { value: 'CLAUDE', label: 'Claude CLI' },
  { value: 'CODEX', label: 'Codex CLI' },
];

interface WorkflowDetailSidebarProps {
  workflow: Workflow;
  runs: PageResponse<WorkflowRun> | undefined;
  workflowVariables: string[];
  onOpenEditInstructions: () => void;
  onOpenAddSkill: () => void;
  onDeactivateSkill: (workflowId: string, skillId: string) => void;
  onNavigateToRun: (runId: string) => void;
}

export function WorkflowDetailSidebar({
  workflow,
  runs,
  workflowVariables,
  onOpenEditInstructions,
  onOpenAddSkill,
  onDeactivateSkill,
  onNavigateToRun,
}: WorkflowDetailSidebarProps) {
  const t = useT();
  const updateWorkflow = useUpdateWorkflow();

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader
          title={t.workflows.instructions}
          action={
            <Button
              size="sm"
              variant="ghost"
              icon={<Pencil className="w-3.5 h-3.5" />}
              onClick={onOpenEditInstructions}
            >
              {t.common.edit}
            </Button>
          }
        />
        {workflow.instructions ? (
          <pre className="text-xs text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded-lg p-3 max-h-40 overflow-y-auto">
            {workflow.instructions}
          </pre>
        ) : (
          <p className="text-sm text-text-tertiary flex items-center gap-2">
            <FileText className="w-4 h-4" />
            {t.workflows.noInstructions}
          </p>
        )}
      </Card>

      <WorkflowMemoryPanel workflowId={workflow.id} runs={runs?.content} />

      <Card>
        <CardHeader title={t.workflows.model} />
        <Select
          value={workflow.model || ''}
          onChange={(e) => {
            updateWorkflow.mutate({ id: workflow.id, data: { model: e.target.value } });
          }}
          options={MODEL_OPTIONS.map((opt) => ({
            value: opt.value,
            label: opt.value === '' ? t.workflows.modelDefault : opt.label,
          }))}
        />
        <p className="text-xs text-text-tertiary mt-2 flex items-center gap-1.5">
          <Bot className="w-3.5 h-3.5" />
          {t.workflows.modelHint}
        </p>
      </Card>

      <Card>
        <CardHeader title={t.workflows.provider} />
        <Select
          value={workflow.provider || ''}
          onChange={(e) => {
            updateWorkflow.mutate({ id: workflow.id, data: { provider: e.target.value || undefined } });
          }}
          options={PROVIDER_OPTIONS.map((opt) => ({
            value: opt.value,
            label: opt.value === '' ? t.workflows.providerDefault : opt.label,
          }))}
        />
        <p className="text-xs text-text-tertiary mt-2 flex items-center gap-1.5">
          <Bot className="w-3.5 h-3.5" />
          {t.workflows.providerHint}
        </p>
      </Card>

      {workflowVariables.length > 0 && (
        <DefaultContextEditor
          workflowId={workflow.id}
          variables={workflowVariables}
          defaultContext={workflow.defaultContext ?? {}}
        />
      )}

      <Card>
        <CardHeader
          title={t.workflows.activatedSkills}
          action={
            <Button
              size="sm"
              variant="ghost"
              icon={<Plus className="w-3.5 h-3.5" />}
              onClick={onOpenAddSkill}
            >
              {t.workflows.activateSkill}
            </Button>
          }
        />
        {workflow.activatedSkillNames.length === 0 ? (
          <p className="text-sm text-text-tertiary">{t.workflows.noSkillsActivated}</p>
        ) : (
          <div className="space-y-2">
            {workflow.activatedSkillIds.map((skillId, idx) => (
              <div key={skillId} className="flex items-center justify-between group">
                <div className="flex items-center gap-2">
                  <Zap className="w-4 h-4 text-accent" />
                  <span className="text-sm text-text">{workflow.activatedSkillNames[idx]}</span>
                </div>
                <button
                  onClick={() => onDeactivateSkill(workflow.id, skillId)}
                  className="p-1 rounded-sm text-text-tertiary hover:text-danger sm:opacity-0 sm:group-hover:opacity-100 transition-all"
                  aria-label={t.skills.deactivate}
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card padding={false}>
        <div className="p-5 pb-0">
          <CardHeader title={`${t.dashboard.recentRuns} (${runs?.content?.length ?? 0})`} />
        </div>
        {!runs?.content?.length ? (
          <div className="px-5 py-8 text-center text-sm text-text-tertiary">{t.workflows.noRuns}</div>
        ) : (
          <div className="divide-y divide-border">
            {runs.content.slice(0, 5).map((run: WorkflowRun) => (
              <div
                key={run.id}
                className="flex items-center justify-between px-5 py-3 hover:bg-surface transition-colors cursor-pointer"
                onClick={() => onNavigateToRun(run.id)}
              >
                <StatusBadge status={run.status} />
                <div className="flex items-center gap-2 text-xs text-text-tertiary">
                  <Clock className="w-3 h-3" />
                  {formatDuration(run.durationMs)}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card>
        <CardHeader title={t.common.info} />
        <div className="space-y-2 text-sm">
          <div className="flex justify-between">
            <span className="text-text-secondary">{t.common.project}</span>
            <span className="text-text">{workflow.projectName}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-text-secondary">{t.common.version}</span>
            <span className="text-text font-mono">{workflow.version}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-text-secondary">{t.common.created}</span>
            <span className="text-text">{new Date(workflow.createdAt).toLocaleDateString()}</span>
          </div>
        </div>
      </Card>
    </div>
  );
}

function DefaultContextEditor({
  workflowId,
  variables,
  defaultContext,
}: {
  workflowId: string;
  variables: string[];
  defaultContext: Record<string, string>;
}) {
  const t = useT();
  const updateWorkflow = useUpdateWorkflow();
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(variables.map((v) => [v, defaultContext[v] ?? '']))
  );

  const contextKey = JSON.stringify(defaultContext);
  const [prevContextKey, setPrevContextKey] = useState(contextKey);
  if (contextKey !== prevContextKey) {
    setPrevContextKey(contextKey);
    setValues(Object.fromEntries(variables.map((v) => [v, defaultContext[v] ?? ''])));
  }

  const hasChanges = variables.some((v) => (values[v] ?? '') !== (defaultContext[v] ?? ''));

  const handleSave = () => {
    const ctx: Record<string, string> = {};
    for (const v of variables) {
      if (values[v]?.trim()) {
        ctx[v] = values[v];
      }
    }
    updateWorkflow.mutate({ id: workflowId, data: { defaultContext: ctx } });
  };

  return (
    <Card>
      <CardHeader
        title={t.workflows.defaultValues}
        action={
          hasChanges ? (
            <Button
              size="sm"
              variant="secondary"
              onClick={handleSave}
              loading={updateWorkflow.isPending}
            >
              {t.common.save}
            </Button>
          ) : undefined
        }
      />
      <div className="space-y-3">
        <p className="text-xs text-text-tertiary">{t.workflows.defaultValuesHint}</p>
        {variables.map((variable) => (
          <Input
            key={variable}
            label={variable}
            value={values[variable] ?? ''}
            onChange={(e) => setValues({ ...values, [variable]: e.target.value })}
            placeholder={`${t.workflows.enterValue} ${variable}`}
          />
        ))}
      </div>
    </Card>
  );
}
