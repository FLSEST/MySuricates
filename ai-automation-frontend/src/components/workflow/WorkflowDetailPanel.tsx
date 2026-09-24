import { Play, Pencil, Download, Save, FlaskConical } from 'lucide-react';
import { Button } from '../ui';
import { ExecutionBadges } from '../shared/ExecutionBadges';
import { WorkflowIcon } from '../../utils/workflowIcon';
import { useSimilarWorkflows, useUpdateWorkflow, exportWorkflow } from '../../hooks/useWorkflows';
import { TagEditor } from './TagEditor';
import { SealBadge } from './SealStateIcon';
import { WorkflowBlockList } from './WorkflowBlockList';
import { WorkflowAutomationSettings } from './WorkflowAutomationSettings';
import { WorkflowRecentRuns } from './WorkflowRecentRuns';
import { useSealStatus } from '../../hooks/useSealStatus';
import { formatTimestamp } from '../../utils/localeFormat';
import type { useNavigate } from 'react-router-dom';
import type { Workflow } from '../../types';
import type { TranslationKeys } from '../../i18n';
import { useI18nStore } from '../../i18n';

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
  const sealStatus = useSealStatus(workflow.id);
  const locale = useI18nStore((s) => s.locale);
  const { data: similarWorkflows = [] } = useSimilarWorkflows(workflow.id, 5);
  const updateWorkflow = useUpdateWorkflow();

  return (
    <div className="bg-surface border border-border rounded-xl p-6 space-y-6">
      <div className="flex items-start gap-3">
        <span className="text-2xl shrink-0">
          <WorkflowIcon icon={workflow.icon} className="w-7 h-7" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-bold text-text truncate">{workflow.name}</h2>
            <SealBadge sealed={workflow.sealed} status={sealStatus} />
            {workflow.testStatus === 'PASSED' && (
              <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-success/10 text-success text-[10px] font-medium" title={workflow.lastTestedAt ? formatTimestamp(workflow.lastTestedAt, locale) : undefined}>
                <FlaskConical className="w-3 h-3" />
                {t.testBadge.passed}
              </span>
            )}
            {workflow.testStatus === 'FAILED' && (
              <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-danger/10 text-danger text-[10px] font-medium" title={workflow.lastTestedAt ? formatTimestamp(workflow.lastTestedAt, locale) : undefined}>
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

      {workflow.blocks.length > 0 && <WorkflowBlockList workflowId={workflow.id} blocks={workflow.blocks} />}

      <WorkflowAutomationSettings workflow={workflow} />

      <WorkflowRecentRuns
        variant="list"
        workflowId={workflow.id}
        onOpenRun={(runId) => navigate(`/workflows/${workflow.id}/run/${runId}`)}
      />

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

      <TagEditor
        t={t}
        tags={workflow.tags ?? []}
        category={workflow.category ?? null}
        userEdited={!!workflow.tagsUserEdited}
        disabled={updateWorkflow.isPending}
        onSave={(next) =>
          updateWorkflow.mutate({
            id: workflow.id,
            data: { tags: next.tags, category: next.category ?? undefined },
          })
        }
      />

      {similarWorkflows.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-text mb-2">{t.workflows.similarWorkflows}</h3>
          <div className="space-y-1.5">
            {similarWorkflows.map((s) => (
              <button
                key={s.workflowId}
                onClick={() => navigate(`/workflows/${s.workflowId}`)}
                className="flex items-center gap-3 w-full px-3 py-2 rounded-lg hover:bg-surface-raised transition-colors text-sm text-left"
              >
                <span className="truncate flex-1 text-text">{s.workflowName}</span>
                <span
                  className="text-text-tertiary text-xs tabular-nums shrink-0"
                  title={t.workflows.similarityMatch.replace('{percent}', String(s.similarity))}
                >
                  {s.similarity}%
                </span>
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="flex gap-2 pt-2 border-t border-border">
        <Button icon={<Play className="w-4 h-4" />} onClick={onExecute}>
          {t.workflows.execute}
        </Button>
        <Button variant="secondary" icon={<Pencil className="w-4 h-4" />} onClick={onEdit} disabled={!!workflow.sealed}
          title={workflow.sealed ? t.workflows.sealedLocked : undefined}>
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
