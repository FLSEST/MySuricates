import {
  Play,
  Pencil,
  Clock,
  Download,
  Save,
  FlaskConical,
  Terminal,
  Sparkles,
  Brain,
  Repeat,
  GitBranch,
  RefreshCw,
  ShieldCheck,
  Layers,
  MessageSquare,
  Route,
  RotateCcw,
  Wrench,
} from 'lucide-react';
import { Button, Spinner } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { ExecutionBadges } from '../shared/ExecutionBadges';
import { WorkflowIcon } from '../../utils/workflowIcon';
import { useWorkflowRuns } from '../../hooks/useWorkflows';
import { useUpdateWorkflow, exportWorkflow } from '../../hooks/useWorkflows';
import { formatDuration, formatDateTime } from '../../utils/formatTime';
import type { useNavigate } from 'react-router-dom';
import type { Workflow } from '../../types';
import type { TranslationKeys } from '../../i18n';

const BLOCK_TYPE_ICONS: Record<string, React.ReactNode> = {
  shell: <Terminal className="w-3.5 h-3.5" />,
  simple: <Sparkles className="w-3.5 h-3.5" />,
  agentic: <Brain className="w-3.5 h-3.5" />,
  foreach: <Repeat className="w-3.5 h-3.5" />,
  conditional: <GitBranch className="w-3.5 h-3.5" />,
  while: <RefreshCw className="w-3.5 h-3.5" />,
  approval: <ShieldCheck className="w-3.5 h-3.5" />,
  'sub-workflow': <Layers className="w-3.5 h-3.5" />,
  'human-input': <MessageSquare className="w-3.5 h-3.5" />,
  switch: <Route className="w-3.5 h-3.5" />,
  retry: <RotateCcw className="w-3.5 h-3.5" />,
  'skill-capability': <Wrench className="w-3.5 h-3.5" />,
};

function getBlockLabel(block: { type: string; config: Record<string, unknown> }): string {
  const prompt = block.config.prompt as string | undefined;
  const command = block.config.command as string | undefined;
  const label = prompt || command || '';
  return label.length > 60 ? label.slice(0, 60) + '…' : label;
}

function pluralize(count: number, singular: string, plural: string) {
  return count === 1 ? singular : plural;
}

interface WorkflowDetailPanelProps {
  workflow: Workflow;
  t: TranslationKeys;
  onExecute: () => void;
  onEdit: () => void;
  onSaveAsTemplate: () => void;
  saveAsTemplateLoading: boolean;
  navigate: ReturnType<typeof useNavigate>;
}

export function WorkflowDetailPanel({
  workflow,
  t,
  onExecute,
  onEdit,
  onSaveAsTemplate,
  saveAsTemplateLoading,
  navigate,
}: WorkflowDetailPanelProps) {
  const { data: runsPage, isLoading: runsLoading } = useWorkflowRuns(workflow.id, 0, 5);
  const recentRuns = runsPage?.content ?? [];
  const updateWorkflow = useUpdateWorkflow();
  const autoTodoEnabled = !!workflow.metadata?.autoTodo?.enabled;
  const autoTodoMode = workflow.metadata?.autoTodo?.mode ?? 'auto';
  const autoRepairEnabled = !!workflow.metadata?.autoRepair?.enabled;
  const autoRepairMode = workflow.metadata?.autoRepair?.mode ?? 'auto';

  return (
    <div className="bg-surface border border-border rounded-xl p-6 space-y-6">
      <div className="flex items-start gap-3">
        <span className="text-2xl shrink-0">
          <WorkflowIcon icon={workflow.icon} className="w-7 h-7" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-bold text-text truncate">{workflow.name}</h2>
            {workflow.testStatus === 'PASSED' && (
              <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-success/10 text-success text-[10px] font-medium" title={workflow.lastTestedAt ? new Date(workflow.lastTestedAt).toLocaleString() : undefined}>
                <FlaskConical className="w-3 h-3" />
                {t.testBadge.passed}
              </span>
            )}
            {workflow.testStatus === 'FAILED' && (
              <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-danger/10 text-danger text-[10px] font-medium" title={workflow.lastTestedAt ? new Date(workflow.lastTestedAt).toLocaleString() : undefined}>
                <FlaskConical className="w-3 h-3" />
                {t.testBadge.failed}
              </span>
            )}
          </div>
          <p className="text-sm text-text-tertiary">
            {workflow.projectName}
            {workflow.projectSubtitle && (
              <span className="text-text-tertiary/60"> · {workflow.projectSubtitle}</span>
            )}
          </p>
        </div>
      </div>

      {workflow.projectDescription && (
        <p className="text-xs text-text-tertiary italic">{workflow.projectDescription}</p>
      )}

      {workflow.description && (
        <p className="text-sm text-text-secondary">{workflow.description}</p>
      )}

      <div className="flex flex-wrap gap-2">
        <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-surface-raised border border-border text-sm">
          <span className="text-text-tertiary font-medium">
            {workflow.blocks.length}{' '}
            {pluralize(workflow.blocks.length, t.workflows.block, t.workflows.blocks)}
          </span>
        </div>
        <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-surface-raised border border-border text-sm">
          <span className="text-text-tertiary font-medium">
            {workflow.runCount}{' '}
            {pluralize(workflow.runCount, t.common.run, t.common.runs)}
          </span>
        </div>
        <ExecutionBadges blocks={workflow.blocks} />
        {workflow.model && (
          <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-surface-raised border border-border text-sm">
            <span className="text-text-tertiary font-medium">{workflow.model}</span>
          </div>
        )}
      </div>

      {workflow.blocks.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-text mb-2">{t.workflows.blocks}</h3>
          <div className="space-y-1">
            {workflow.blocks.map((block, idx) => (
              <div
                key={idx}
                className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-surface-raised border border-border text-xs"
              >
                <span className="text-text-tertiary shrink-0">
                  {BLOCK_TYPE_ICONS[block.type] ?? <Sparkles className="w-3.5 h-3.5" />}
                </span>
                <span className="text-text-secondary font-medium shrink-0">{block.type}</span>
                {getBlockLabel(block) && (
                  <span className="text-text-tertiary truncate font-mono">{getBlockLabel(block)}</span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="flex items-center justify-between px-3 py-2 rounded-lg bg-surface-raised border border-border">
        <div>
          <span className="text-sm font-medium text-text">{t.workflows.autoTodo}</span>
          <p className="text-xs text-text-tertiary">{t.workflows.autoTodoDescription}</p>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={autoTodoEnabled}
          onClick={() =>
            updateWorkflow.mutate({
              id: workflow.id,
              data: { metadata: { autoTodo: { ...workflow.metadata?.autoTodo, enabled: !autoTodoEnabled } } },
            })
          }
          className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors ${
            autoTodoEnabled ? 'bg-accent' : 'bg-border'
          }`}
        >
          <span
            className={`pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow transform transition-transform ${
              autoTodoEnabled ? 'translate-x-4' : 'translate-x-0'
            }`}
          />
        </button>
      </div>

      {autoTodoEnabled && (
        <div className="px-3 py-2 rounded-lg bg-surface-raised border border-border space-y-1">
          <span className="text-xs font-medium text-text-secondary">{t.workflows.autoTodoMode}</span>
          <select
            value={autoTodoMode}
            onChange={(e) =>
              updateWorkflow.mutate({
                id: workflow.id,
                data: { metadata: { autoTodo: { ...workflow.metadata?.autoTodo, mode: e.target.value as 'auto' | 'approval' | 'track' } } },
              })
            }
            className="w-full text-xs bg-surface border border-border rounded-md px-2 py-1.5 text-text focus:outline-none focus:ring-1 focus:ring-accent"
          >
            <option value="auto">{t.workflows.autoTodoModeAuto}</option>
            <option value="approval">{t.workflows.autoTodoModeApproval}</option>
            <option value="track">{t.workflows.autoTodoModeTrack}</option>
          </select>
          <p className="text-xs text-text-tertiary">{t.workflows.autoTodoModeHint}</p>
        </div>
      )}

      <div className="flex items-center justify-between px-3 py-2 rounded-lg bg-surface-raised border border-border">
        <div>
          <span className="text-sm font-medium text-text">{t.workflows.autoRepair}</span>
          <p className="text-xs text-text-tertiary">{t.workflows.autoRepairDescription}</p>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={autoRepairEnabled}
          onClick={() =>
            updateWorkflow.mutate({
              id: workflow.id,
              data: { metadata: { autoRepair: { ...workflow.metadata?.autoRepair, enabled: !autoRepairEnabled } } },
            })
          }
          className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors ${
            autoRepairEnabled ? 'bg-accent' : 'bg-border'
          }`}
        >
          <span
            className={`pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow transform transition-transform ${
              autoRepairEnabled ? 'translate-x-4' : 'translate-x-0'
            }`}
          />
        </button>
      </div>

      {autoRepairEnabled && (
        <div className="px-3 py-2 rounded-lg bg-surface-raised border border-border space-y-1">
          <span className="text-xs font-medium text-text-secondary">{t.workflows.autoRepairMode}</span>
          <select
            value={autoRepairMode}
            onChange={(e) =>
              updateWorkflow.mutate({
                id: workflow.id,
                data: { metadata: { autoRepair: { ...workflow.metadata?.autoRepair, mode: e.target.value } } },
              })
            }
            className="w-full text-xs bg-surface border border-border rounded-md px-2 py-1.5 text-text focus:outline-none focus:ring-1 focus:ring-accent"
          >
            <option value="auto">{t.workflows.autoRepairModeAuto}</option>
            <option value="test">{t.workflows.autoRepairModeTest}</option>
            <option value="local">{t.workflows.autoRepairModeLocal}</option>
          </select>
          <p className="text-xs text-text-tertiary">{t.workflows.autoRepairModeHint}</p>
        </div>
      )}

      <div>
        <h3 className="text-sm font-semibold text-text mb-2 flex items-center gap-1.5">
          <Clock className="w-4 h-4 text-text-tertiary" />
          {t.dashboard.recentRuns}
        </h3>
        {runsLoading ? (
          <div className="flex justify-center py-4">
            <Spinner />
          </div>
        ) : recentRuns.length === 0 ? (
          <p className="text-xs text-text-tertiary py-2">{t.workflows.noRuns}</p>
        ) : (
          <div className="space-y-1.5">
            {recentRuns.map((run) => (
              <button
                key={run.id}
                onClick={() => navigate(`/workflows/${workflow.id}/run/${run.id}`)}
                className="flex items-center gap-3 w-full px-3 py-2 rounded-lg hover:bg-surface-raised transition-colors text-sm text-left"
              >
                <StatusBadge status={run.status} />
                <span className="text-text-tertiary text-xs tabular-nums">
                  {formatDateTime(run.startedAt)}
                </span>
                {run.durationMs != null && (
                  <span className="text-text-tertiary/60 text-xs tabular-nums">
                    {formatDuration(run.durationMs)}
                  </span>
                )}
              </button>
            ))}
          </div>
        )}
      </div>

      {workflow.activatedSkillNames.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-text mb-2">{t.workflows.activatedSkills}</h3>
          <div className="flex flex-wrap gap-1.5">
            {workflow.activatedSkillNames.map((name) => (
              <span
                key={name}
                className="inline-flex items-center px-2 py-0.5 rounded-sm text-xs font-medium bg-accent/10 text-accent"
              >
                {name}
              </span>
            ))}
          </div>
        </div>
      )}

      <div className="flex gap-2 pt-2 border-t border-border">
        <Button icon={<Play className="w-4 h-4" />} onClick={onExecute}>
          {t.workflows.execute}
        </Button>
        <Button variant="secondary" icon={<Pencil className="w-4 h-4" />} onClick={onEdit}>
          {t.common.edit}
        </Button>
        <Button
          variant="ghost"
          size="sm"
          icon={<Download className="w-4 h-4" />}
          onClick={() => {
            const safeName = workflow.name.replace(/[^a-zA-Z0-9_-]/g, '_');
            exportWorkflow(workflow.id, `${safeName}.json`);
          }}
          title={t.workflows.exportWorkflow}
          aria-label={t.workflows.exportWorkflow}
        />
        <Button
          variant="ghost"
          size="sm"
          icon={<Save className="w-4 h-4" />}
          onClick={onSaveAsTemplate}
          loading={saveAsTemplateLoading}
          title={t.templates.saveAsTemplate}
          aria-label={t.templates.saveAsTemplate}
        />
      </div>
    </div>
  );
}
