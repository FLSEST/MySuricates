import {
  Play,
  Trash2,
  CheckSquare,
  Square,
  Bell,
  FlaskConical,
  ShieldCheck,
  Cloud,
  Workflow as WorkflowFlowIcon,
} from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import { ExecutionBadges } from '../shared/ExecutionBadges';
import { WorkflowIcon } from '../../utils/workflowIcon';
import { SealStateIcon } from './SealStateIcon';
import { auditConfigOf } from './auditWorkflow';
import { useSealStatus } from '../../hooks/useSealStatus';
import type { useNavigate } from 'react-router-dom';
import type { Workflow } from '../../types';

interface WorkflowRowProps {
  workflow: Workflow;
  selected: boolean;
  onSelect: () => void;
  onExecute: () => void;
  onDelete: () => void;
  navigate: ReturnType<typeof useNavigate>;
  selectMode: boolean;
  isChecked: boolean;
  onToggleCheck: () => void;
  showDetails?: boolean;
}

export function WorkflowRow({
  workflow,
  selected,
  onSelect,
  onExecute,
  onDelete,
  navigate,
  selectMode,
  isChecked,
  onToggleCheck,
  showDetails = false,
}: WorkflowRowProps) {
  const t = useT();
  const audit = auditConfigOf(workflow.metadata);
  const locale = useI18nStore((s) => s.locale);
  const sealStatus = useSealStatus(workflow.id);
  const handleClick = () => {
    if (selectMode) {
      onToggleCheck();
      return;
    }
    if (window.innerWidth < 1024) {
      navigate(`/workflows/${workflow.id}`);
    } else {
      onSelect();
    }
  };

  return (
    <div
      className={`group flex items-center gap-2 w-full px-2 py-1.5 rounded-md text-left transition-colors cursor-pointer ${
        selectMode && isChecked
          ? 'bg-accent/10 text-accent border border-accent/20'
          : selected && !selectMode
            ? 'bg-accent/10 text-accent border border-accent/20'
            : 'hover:bg-surface-raised text-text border border-transparent'
      }`}
      onClick={handleClick}
    >
      {selectMode && (
        <span className="shrink-0">
          {isChecked ? (
            <CheckSquare className="w-4 h-4 text-accent" />
          ) : (
            <Square className="w-4 h-4 text-text-tertiary" />
          )}
        </span>
      )}
      <span className="text-base shrink-0">
        <WorkflowIcon icon={workflow.icon} className="w-4 h-4" />
      </span>
      {showDetails && workflow.description ? (
        <div className="flex-1 min-w-0">
          <div className="text-sm truncate">{workflow.name}</div>
          <div className="text-[11px] text-text-tertiary truncate">{workflow.description}</div>
        </div>
      ) : (
        <span className="text-sm truncate flex-1">{workflow.name}</span>
      )}
      {!selectMode && (
        <span className="shrink-0 inline-flex" data-testid="row-sealed">
          <SealStateIcon sealed={workflow.sealed} status={sealStatus} />
        </span>
      )}
      {!selectMode && audit && (
        <span className="inline-flex items-center gap-0.5 px-1 py-0.5 rounded-sm bg-accent/10 text-accent text-[9px] font-semibold shrink-0"
          title={t.workflows.auditWorkflowBadge.replace('{key}', audit.key)} data-testid="row-audit">
          <ShieldCheck className="w-2.5 h-2.5" aria-hidden="true" />
          {/* The word gives way when the row shows its actions; the panel then names the audit in full. */}
          <span className={selected ? 'hidden' : 'sm:group-hover:hidden'} aria-hidden="true">{t.workflows.auditLabel}</span>
          <span className="sr-only">{t.workflows.auditWorkflowBadge.replace('{key}', audit.key)}</span>
        </span>
      )}
      {!selectMode && !audit && !!workflow.metadata?.autoTodo?.enabled && (
        <Bell className="w-3 h-3 text-accent/50 shrink-0" />
      )}
      {!selectMode && workflow.testStatus === 'PASSED' && (
        <span className="inline-flex items-center gap-0.5 px-1 py-0.5 rounded-sm bg-success/10 text-success text-[9px] font-medium shrink-0" title={workflow.lastTestedAt ? formatTimestamp(workflow.lastTestedAt, locale) : undefined}>
          <FlaskConical className="w-2.5 h-2.5" />
        </span>
      )}
      {!selectMode && workflow.testStatus === 'FAILED' && (
        <span className="inline-flex items-center gap-0.5 px-1 py-0.5 rounded-sm bg-danger/10 text-danger text-[9px] font-medium shrink-0" title={workflow.lastTestedAt ? formatTimestamp(workflow.lastTestedAt, locale) : undefined}>
          <FlaskConical className="w-2.5 h-2.5" />
        </span>
      )}
      {!selectMode && workflow.stableSyncedAt && workflow.updatedAt <= workflow.stableSyncedAt && (
        <span className="inline-flex items-center gap-0.5 px-1 py-0.5 rounded-sm bg-success/10 text-success text-[9px] font-medium shrink-0" title={t.workflows.stableSynced.replace('{date}', formatTimestamp(workflow.stableSyncedAt, locale))}>
          <Cloud className="w-2.5 h-2.5" />
        </span>
      )}
      {!selectMode && workflow.stableSyncedAt && workflow.updatedAt > workflow.stableSyncedAt && (
        <span className="inline-flex items-center gap-0.5 px-1 py-0.5 rounded-sm bg-warning/10 text-warning text-[9px] font-medium shrink-0" title={t.workflows.stableChangedSince.replace('{date}', formatTimestamp(workflow.stableSyncedAt, locale))}>
          <Cloud className="w-2.5 h-2.5" />
        </span>
      )}
      {!selectMode && (
        <>
          <ExecutionBadges blocks={workflow.blocks} />
          {workflow.runCount > 0 && (
            <span className="text-[10px] tabular-nums text-text-tertiary bg-surface-raised px-1.5 py-0.5 rounded-full shrink-0">
              {workflow.runCount}
            </span>
          )}
          <button
            onClick={(e) => { e.stopPropagation(); navigate(`/workflows/${workflow.id}?tab=diagram`); }}
            className="sm:opacity-0 sm:group-hover:opacity-100 p-0.5 rounded-sm hover:bg-accent/20 text-text-tertiary hover:text-accent transition-all shrink-0"
            title={t.workflows.diagramTab}
          >
            <WorkflowFlowIcon className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={(e) => { e.stopPropagation(); onExecute(); }}
            className="sm:opacity-0 sm:group-hover:opacity-100 p-0.5 rounded-sm hover:bg-accent/20 text-accent transition-all shrink-0"
            title={t.workflows.execute}
          >
            <Play className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={(e) => { e.stopPropagation(); onDelete(); }}
            className="sm:opacity-0 sm:group-hover:opacity-100 p-0.5 rounded-sm hover:bg-danger/20 text-text-tertiary hover:text-danger transition-all shrink-0"
            title={t.common.delete}
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </>
      )}
    </div>
  );
}
