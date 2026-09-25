import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Pause, Play, XCircle, FolderOpen, ListTodo, Trash2 } from 'lucide-react';
import { Button } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { GateStatusBadge } from '../shared/GateStatusBadge';
import { ExecutionRerunButton } from './ExecutionRerunButton';
import { OpenApprovalButton } from './OpenApprovalButton';
import {
  usePauseExecution, useResumeExecution, useCancelExecution, useResumeFromStep, useDeleteRun,
} from '../../hooks/useWorkflows';
import { useCreateTodo } from '../../hooks/useTodos';
import { useRunDeletionAllowed } from '../../hooks/useSystemCapabilities';
import { useToast } from '../../hooks/useToast';
import { useI18nStore, useT } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import { buildFailedRunTodo } from '../../utils/failedRunTodo';
import { openPath } from '../../api/client';
import type { Workflow, WorkflowRun } from '../../types';

interface RunHeaderProps {
  run: WorkflowRun;
  workflow: Workflow | undefined;
  /** Workflow id from the route (the run page's `/workflows/:id/run/:runId`). */
  workflowId: string;
  /** Live connection of the log stream. */
  connected: boolean;
  /** RUNNING or PAUSED. */
  isRunning: boolean;
  /** Set while an approval closed without an answer is still open: shows the button that opens it again. */
  onOpenApproval?: () => void;
}

/**
 * Head of the run page: back, status (gate, live marker), workflow link, project folder and the
 * actions by state — answer an approval closed without an answer, TODO from a failed run, resume after
 * an interruption, run again, delete a finished run, and pause/resume/cancel while it runs.
 */
export function RunHeader({ run, workflow, workflowId, connected, isRunning, onOpenApproval }: RunHeaderProps) {
  const navigate = useNavigate();
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const toast = useToast();
  const pauseExecution = usePauseExecution();
  const resumeExecution = useResumeExecution();
  const cancelExecution = useCancelExecution();
  const deleteRun = useDeleteRun();
  const runDeletionAllowed = useRunDeletionAllowed();
  const resumeFromStep = useResumeFromStep();
  const createTodo = useCreateTodo();

  return (
    <div className="flex items-center gap-4">
      <button
        onClick={() => window.history.length > 1 ? navigate(-1) : navigate(`/workflows/${workflowId}`)}
        className="p-2 rounded-lg hover:bg-surface-raised text-text-secondary hover:text-text transition-colors"
        title={t.common.back}
        aria-label={t.common.back}
      >
        <ArrowLeft className="w-5 h-5" />
      </button>
      <div className="flex-1">
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-bold text-text">{t.workflows.execution.title}</h1>
          <StatusBadge status={run.status} />
          <GateStatusBadge run={run} />
          {connected && isRunning && (
            <span className="flex items-center gap-1 text-xs text-success">
              <span className="w-2 h-2 rounded-full bg-success animate-pulse" />
              {t.common.live}
            </span>
          )}
        </div>
        <p className="text-sm text-text-secondary mt-0.5 flex items-center gap-2">
          <span>
            <button
              onClick={() => navigate(`/workflows/${workflowId}`)}
              className="text-accent hover:text-accent-hover hover:underline transition-colors"
            >
              {workflow?.name}
            </button>
            {' '}&middot; {formatTimestamp(run.startedAt, locale)}
          </span>
          {run.projectPath && (
            <button
              onClick={() => openPath(run.projectPath!).catch(() => toast.error(t.workflows.execution.openPathFailed))}
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium bg-surface-raised hover:bg-surface-raised/80 text-text-secondary hover:text-text transition-colors"
              title={run.projectPath}
            >
              <FolderOpen className="w-3.5 h-3.5" />
              {t.workflows.execution.openFolder}
            </button>
          )}
        </p>
      </div>
      {onOpenApproval && <OpenApprovalButton onClick={onOpenApproval} />}
      {(run.status === 'INTERRUPTED' || run.status === 'FAILED') && (
        <div className="flex items-center gap-2">
          {run.status === 'FAILED' && (
            <Button
              variant="secondary" size="sm"
              icon={<ListTodo className="w-4 h-4" />}
              onClick={() => createTodo.mutate(buildFailedRunTodo(run, workflow, workflowId, t, locale))}
              loading={createTodo.isPending}
            >
              {t.workflows.execution.createTodo}
            </Button>
          )}
          {run.status === 'INTERRUPTED' && run.steps?.some(s => s.status === 'COMPLETED') && (
            <Button
              variant="primary" size="sm"
              icon={<Play className="w-4 h-4" />}
              onClick={() => resumeFromStep.mutate(run.id)}
              loading={resumeFromStep.isPending}
            >
              {t.workflows.execution.resumeFromStep}
            </Button>
          )}
        </div>
      )}
      <ExecutionRerunButton run={run} />
      {!isRunning && runDeletionAllowed && (
        <Button
          variant="secondary" size="sm"
          icon={<Trash2 className="w-4 h-4" />}
          loading={deleteRun.isPending}
          onClick={() => {
            if (!window.confirm(t.runs.deleteConfirm)) return;
            // Der Lauf existiert gleich nicht mehr — weg von seiner Detailseite.
            deleteRun.mutate(run.id, { onSuccess: () => navigate(`/workflows/${workflowId}`) });
          }}
        >
          {t.runs.delete}
        </Button>
      )}
      {isRunning && (
        <div className="flex items-center gap-2">
          {run.status === 'RUNNING' && (
            <Button
              variant="secondary" size="sm"
              icon={<Pause className="w-4 h-4" />}
              onClick={() => pauseExecution.mutate(run.id)}
              loading={pauseExecution.isPending}
            >
              {t.workflows.execution.pause}
            </Button>
          )}
          {run.status === 'PAUSED' && (
            <Button
              size="sm"
              icon={<Play className="w-4 h-4" />}
              onClick={() => resumeExecution.mutate(run.id)}
              loading={resumeExecution.isPending}
            >
              {t.workflows.execution.resume}
            </Button>
          )}
          <Button
            variant="danger" size="sm"
            icon={<XCircle className="w-4 h-4" />}
            onClick={() => cancelExecution.mutate(run.id)}
            loading={cancelExecution.isPending}
          >
            {t.workflows.execution.cancel}
          </Button>
        </div>
      )}
    </div>
  );
}
