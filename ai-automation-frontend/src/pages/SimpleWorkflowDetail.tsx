import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Play, Clock, CheckCircle2, AlertCircle } from 'lucide-react';
import { Card, CardHeader, Button, Spinner } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { ErrorState } from '../components/shared/ErrorState';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import {
  useWorkflow, useWorkflowRuns, useExecuteWorkflow,
} from '../hooks/useWorkflows';
import { useT, useI18nStore } from '../i18n';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import { readContextSchema } from '../utils/contextSchema';
import { formatDuration, formatRelativeTime } from '../utils/formatTime';

export function SimpleWorkflowDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  const { data: workflow, isLoading, error, refetch } = useWorkflow(id!);
  const { data: runs } = useWorkflowRuns(id!);
  const executeWorkflow = useExecuteWorkflow();

  const [showExecuteDialog, setShowExecuteDialog] = useState(false);

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  if (error || !workflow) {
    return <ErrorState message={error?.message} onRetry={() => refetch()} />;
  }

  const workflowVariables = extractWorkflowVariables(workflow.blocks);

  const handleExecute = () => {
    if (workflowVariables.length > 0) {
      setShowExecuteDialog(true);
    } else {
      executeWorkflow.mutate(
        { id: workflow.id },
        { onSuccess: (run) => navigate(`/workflows/${workflow.id}/run/${run.id}`) },
      );
    }
  };

  const handleExecuteWithContext = (context: Record<string, string>) => {
    executeWorkflow.mutate(
      { id: workflow.id, context },
      {
        onSuccess: (run) => {
          setShowExecuteDialog(false);
          navigate(`/workflows/${workflow.id}/run/${run.id}`);
        },
      },
    );
  };

  const recentRuns = runs?.content?.slice(0, 6) ?? [];

  return (
    <div className="space-y-6 animate-fade-in max-w-2xl">
      {/* Header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/workflows')}
          className="p-2 rounded-lg hover:bg-surface-raised text-text-secondary hover:text-text transition-colors"
          aria-label={t.common.back}
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div className="flex items-center gap-3 min-w-0">
          <span className="text-2xl">{workflow.icon || '⚡'}</span>
          <div className="min-w-0">
            <h1 className="text-xl font-bold text-text truncate">{workflow.name}</h1>
            {workflow.description && (
              <p className="text-sm text-text-secondary truncate">{workflow.description}</p>
            )}
          </div>
        </div>
      </div>

      {/* Execute button */}
      <Button
        className="w-full"
        icon={<Play className="w-4 h-4" />}
        onClick={handleExecute}
        loading={executeWorkflow.isPending}
        size="lg"
      >
        {t.workflows.execute}
      </Button>

      {/* Info */}
      <Card>
        <CardHeader title={t.common.info} />
        <dl className="space-y-2 text-sm">
          <div className="flex justify-between">
            <dt className="text-text-secondary">{t.common.project}</dt>
            <dd className="text-text font-medium">{workflow.projectName}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-text-secondary">{t.workflows.blocks}</dt>
            <dd className="text-text font-medium">{workflow.blocks.length}</dd>
          </div>
          {workflow.model && (
            <div className="flex justify-between">
              <dt className="text-text-secondary">{t.workflows.model}</dt>
              <dd className="text-text font-medium font-mono text-xs">{workflow.model}</dd>
            </div>
          )}
        </dl>
      </Card>

      {/* Recent runs */}
      <Card padding={false}>
        <div className="p-5 pb-0">
          <CardHeader title={`${t.dashboard.recentRuns} (${recentRuns.length})`} />
        </div>
        {recentRuns.length === 0 ? (
          <div className="px-5 py-8 text-center text-sm text-text-tertiary">{t.workflows.noRuns}</div>
        ) : (
          <div className="divide-y divide-border">
            {recentRuns.map((run) => (
              <div
                key={run.id}
                className="flex items-center justify-between px-5 py-3 hover:bg-surface transition-colors cursor-pointer"
                onClick={() => navigate(`/workflows/${workflow.id}/run/${run.id}`)}
              >
                <div className="flex items-center gap-3 min-w-0">
                  {run.status === 'COMPLETED' ? (
                    <CheckCircle2 className="w-4 h-4 text-success shrink-0" />
                  ) : run.status === 'FAILED' ? (
                    <AlertCircle className="w-4 h-4 text-danger shrink-0" />
                  ) : (
                    <StatusBadge status={run.status} />
                  )}
                  <span className="text-sm text-text-secondary truncate">
                    {run.startedAt ? formatRelativeTime(run.startedAt, locale) : '-'}
                  </span>
                </div>
                <div className="flex items-center gap-1 text-xs text-text-tertiary shrink-0">
                  <Clock className="w-3 h-3" />
                  <span>{formatDuration(run.durationMs)}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <ExecuteDialog
        open={showExecuteDialog}
        onClose={() => setShowExecuteDialog(false)}
        onExecute={handleExecuteWithContext}
        variables={workflowVariables}
        defaultValues={workflow.defaultContext}
        optionalVariables={readContextSchema(workflow).optional}
        descriptions={readContextSchema(workflow).descriptions}
        placeholders={readContextSchema(workflow).placeholders}
        loading={executeWorkflow.isPending}
      />
    </div>
  );
}
