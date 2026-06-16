import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Play } from 'lucide-react';
import { useT } from '../i18n';
import { useWorkflows, useExecuteWorkflow, useAllRuns } from '../hooks/useWorkflows';
import { useSettingsStore } from '../stores/settingsStore';
import { matchWorkflows } from '../utils/matchWorkflows';
import { curateWorkflows } from '../utils/curateWorkflows';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import { readContextSchema } from '../utils/contextSchema';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import { Spinner, Button } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { SimpleOnboarding } from '../components/simple/SimpleOnboarding';
import type { Workflow } from '../types';

const TERMINAL_OK = new Set(['COMPLETED', 'COMPLETED_WITH_ERRORS']);

export function SimpleHome() {
  const t = useT();
  const navigate = useNavigate();
  const { pinnedWorkflowIds } = useSettingsStore();
  const { data: workflows, isLoading, error, refetch } = useWorkflows();
  const { data: runsPage } = useAllRuns({ size: 100 });
  const executeWorkflow = useExecuteWorkflow();

  const [intent, setIntent] = useState('');
  const [executeDialog, setExecuteDialog] = useState<{ open: boolean; workflow: Workflow | null; variables: string[] }>(
    { open: false, workflow: null, variables: [] },
  );

  const matches = useMemo(() => matchWorkflows(intent, workflows ?? []).slice(0, 5), [intent, workflows]);
  const chips = useMemo(() => curateWorkflows(workflows ?? [], pinnedWorkflowIds, 4).curated, [workflows, pinnedWorkflowIds]);

  const today = useMemo(() => {
    const runs = runsPage?.content ?? [];
    const todayStr = new Date().toISOString().slice(0, 10);
    const todays = runs.filter((r) => r.startedAt?.slice(0, 10) === todayStr);
    return {
      green: todays.filter((r) => TERMINAL_OK.has(r.status)).length,
      failed: todays.filter((r) => r.status === 'FAILED').length,
    };
  }, [runsPage]);

  const handleExecute = (workflow: Workflow) => {
    const variables = extractWorkflowVariables(workflow.blocks);
    if (variables.length > 0) {
      setExecuteDialog({ open: true, workflow, variables });
    } else {
      executeWorkflow.mutate({ id: workflow.id }, { onSuccess: (run) => navigate(`/workflows/${workflow.id}/run/${run.id}`) });
    }
  };

  const handleExecuteWithContext = (context: Record<string, string>) => {
    if (!executeDialog.workflow) return;
    const id = executeDialog.workflow.id;
    executeWorkflow.mutate(
      { id, context },
      {
        onSuccess: (run) => {
          setExecuteDialog({ open: false, workflow: null, variables: [] });
          navigate(`/workflows/${id}/run/${run.id}`);
        },
      },
    );
  };

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <div className="animate-fade-in max-w-3xl mx-auto">
      <SimpleOnboarding />

      <p className="text-sm text-text-secondary mb-1">{t.simple.home.greeting}</p>
      <h1 className="text-2xl font-bold text-text mb-6">{t.simple.home.question}</h1>

      <input
        type="text"
        value={intent}
        onChange={(e) => setIntent(e.target.value)}
        placeholder={t.simple.home.inputPlaceholder}
        className="w-full text-sm bg-surface border border-border rounded-xl px-4 py-3 text-text focus:outline-none focus:ring-1 focus:ring-accent mb-4"
      />

      {intent.trim() && matches.length > 0 && (
        <div className="space-y-2 mb-6">
          <p className="text-xs text-text-tertiary">{t.simple.home.matchesTitle}</p>
          {matches.map((m) => (
            <div
              key={m.workflow.id}
              className="flex items-center justify-between px-4 py-3 rounded-lg bg-surface border border-border hover:border-accent/40 transition-colors cursor-pointer"
              onClick={() => navigate(`/workflows/${m.workflow.id}`)}
            >
              <div className="flex items-center gap-3 min-w-0">
                <span className="text-lg">{m.workflow.icon || '⚡'}</span>
                <p className="text-sm font-medium text-text truncate">{m.workflow.name}</p>
              </div>
              <Button size="sm" variant="secondary" icon={<Play className="w-3.5 h-3.5" />}
                onClick={(e) => { e.stopPropagation(); handleExecute(m.workflow); }} loading={executeWorkflow.isPending}>
                {t.workflows.execute}
              </Button>
            </div>
          ))}
        </div>
      )}

      {intent.trim() && matches.length === 0 && (
        <button className="text-sm text-accent hover:underline mb-6" onClick={() => navigate('/workflows')}>
          {t.simple.home.noMatch}
        </button>
      )}

      {!intent.trim() && chips.length > 0 && (
        <>
          <p className="text-xs text-text-tertiary mb-2">{t.simple.home.frequentlyUsed}</p>
          <div className="flex gap-2 flex-wrap mb-8">
            {chips.map((w) => (
              <button key={w.id}
                className="flex items-center gap-2 bg-surface border border-border rounded-full px-3.5 py-2 text-sm text-text-secondary hover:border-accent/40 transition-colors"
                onClick={() => handleExecute(w)}>
                <span>{w.icon || '⚡'}</span>{w.name}
              </button>
            ))}
          </div>
        </>
      )}

      <div className="flex items-center justify-between bg-surface border border-border rounded-lg px-4 py-3 cursor-pointer hover:border-accent/40 transition-colors"
        onClick={() => navigate('/runs')}>
        <span className="text-sm text-text-secondary">
          {t.simple.home.todayPrefix}{' '}
          <span className="text-success font-medium">{today.green} {t.simple.home.green}</span>
          {' · '}
          <span className="text-danger font-medium">{today.failed} {t.simple.home.failed}</span>
        </span>
        <span className="text-sm text-accent">{t.simple.home.viewResults} →</span>
      </div>

      <ExecuteDialog
        open={executeDialog.open}
        onClose={() => setExecuteDialog({ open: false, workflow: null, variables: [] })}
        onExecute={handleExecuteWithContext}
        variables={executeDialog.variables}
        defaultValues={executeDialog.workflow?.defaultContext}
        optionalVariables={executeDialog.workflow ? readContextSchema(executeDialog.workflow).optional : undefined}
        descriptions={executeDialog.workflow ? readContextSchema(executeDialog.workflow).descriptions : undefined}
        placeholders={executeDialog.workflow ? readContextSchema(executeDialog.workflow).placeholders : undefined}
        loading={executeWorkflow.isPending}
      />
    </div>
  );
}
