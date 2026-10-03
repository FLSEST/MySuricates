import { useState } from 'react';
import { Plus, Pencil, Zap, FileText, Trash2 } from 'lucide-react';
import { Card, CardHeader, Button, Toggle } from '../ui';
import { WorkflowMemoryPanel } from './WorkflowMemoryPanel';
import { WorkflowAiSettingsCards } from './WorkflowAiSettingsCards';
import { WorkflowAutoResumeCard } from './WorkflowAutoResumeCard';
import { DefaultContextEditor } from './DefaultContextEditor';
import { RECENT_RUNS_PAGE_SIZE, WorkflowRecentRuns } from './WorkflowRecentRuns';
import { useUpdateWorkflow, useWorkflowRuns } from '../../hooks/useWorkflows';
import { useModuleEnabled } from '../../hooks/useModules';
import { useI18nStore, useT } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import type { Workflow } from '../../types';

interface WorkflowDetailSidebarProps {
  workflow: Workflow;
  workflowVariables: string[];
  onOpenEditInstructions: () => void;
  onOpenAddSkill: () => void;
  onDeactivateSkill: (workflowId: string, skillId: string) => void;
  onNavigateToRun: (runId: string) => void;
}

export function WorkflowDetailSidebar({
  workflow,
  workflowVariables,
  onOpenEditInstructions,
  onOpenAddSkill,
  onDeactivateSkill,
  onNavigateToRun,
}: WorkflowDetailSidebarProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const memoryModule = useModuleEnabled('memory');
  const skillsModule = useModuleEnabled('skills');
  const factoryModule = useModuleEnabled('factory');
  const updateWorkflow = useUpdateWorkflow();
  // Sealed definitions are read-only; memory and runs stay usable
  const sealed = !!workflow.sealed;
  const [runPage, setRunPage] = useState(0);
  // Same page as the recent-runs card (one request); it also feeds the memory panel
  const { data: runs } = useWorkflowRuns(workflow.id, runPage, RECENT_RUNS_PAGE_SIZE);

  return (
    <div className="space-y-4">
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
            <span className="text-text">{formatTimestamp(workflow.createdAt, locale, { year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</span>
          </div>
        </div>
      </Card>

      <fieldset disabled={sealed} className="min-w-0 space-y-4" title={sealed ? t.workflows.sealedLocked : undefined}>
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
      </fieldset>

      {memoryModule && <WorkflowMemoryPanel workflowId={workflow.id} runs={runs?.content} runTotal={runs?.totalElements} />}

      <fieldset disabled={sealed} className="min-w-0 space-y-4" title={sealed ? t.workflows.sealedLocked : undefined}>
        <WorkflowAiSettingsCards workflow={workflow} />

        <WorkflowAutoResumeCard workflow={workflow} />

        {factoryModule && (
          <Card>
            <div className="flex items-center justify-between">
              <div>
                <span className="text-sm font-medium text-text">{t.workflows.autoRepair}</span>
                <p className="text-xs text-text-tertiary">{t.workflows.autoRepairDescription}</p>
              </div>
              <Toggle
                enabled={!!workflow.metadata?.autoRepair?.enabled}
                onChange={(enabled) =>
                  updateWorkflow.mutate({
                    id: workflow.id,
                    data: { metadata: { autoRepair: { ...workflow.metadata?.autoRepair, enabled } } },
                  })
                }
                ariaLabel={t.workflows.autoRepair}
              />
            </div>
            {workflow.metadata?.autoRepair?.enabled && (
              <div className="mt-3 space-y-1">
                <span className="text-xs font-medium text-text-secondary">{t.workflows.autoRepairMode}</span>
                <select
                  value={workflow.metadata?.autoRepair?.mode ?? 'auto'}
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
                <div className="mt-2 grid grid-cols-2 gap-2">
                  <div>
                    <label className="text-xs text-text-secondary">{t.workflows.autoRepairMaxRetries}</label>
                    <input
                      type="number"
                      min={1}
                      max={5}
                      value={workflow.metadata?.autoRepair?.maxRetries ?? 2}
                      onChange={(e) => {
                        const val = Math.max(1, Math.min(5, parseInt(e.target.value) || 2));
                        updateWorkflow.mutate({
                          id: workflow.id,
                          data: { metadata: { autoRepair: { ...workflow.metadata?.autoRepair, maxRetries: val } } },
                        });
                      }}
                      className="w-full text-xs bg-surface border border-border rounded-md px-2 py-1.5 text-text focus:outline-none focus:ring-1 focus:ring-accent"
                    />
                  </div>
                  <div>
                    <label className="text-xs text-text-secondary">{t.workflows.autoRepairTimeout}</label>
                    <input
                      type="number"
                      min={60}
                      max={3600}
                      step={60}
                      value={workflow.metadata?.autoRepair?.timeoutSeconds ?? 600}
                      onChange={(e) => {
                        const val = Math.max(60, Math.min(3600, parseInt(e.target.value) || 600));
                        updateWorkflow.mutate({
                          id: workflow.id,
                          data: { metadata: { autoRepair: { ...workflow.metadata?.autoRepair, timeoutSeconds: val } } },
                        });
                      }}
                      className="w-full text-xs bg-surface border border-border rounded-md px-2 py-1.5 text-text focus:outline-none focus:ring-1 focus:ring-accent"
                    />
                  </div>
                </div>
              </div>
            )}
          </Card>
        )}

        {workflowVariables.length > 0 && (
          <DefaultContextEditor
            workflowId={workflow.id}
            variables={workflowVariables}
            defaultContext={workflow.defaultContext ?? {}}
          />
        )}

        {skillsModule && (
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
                      title={t.skills.deactivate}
                      aria-label={t.skills.deactivate}
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </Card>
        )}
      </fieldset>

      <WorkflowRecentRuns
        variant="card"
        workflowId={workflow.id}
        page={runPage}
        onPageChange={setRunPage}
        onOpenRun={onNavigateToRun}
      />

    </div>
  );
}
