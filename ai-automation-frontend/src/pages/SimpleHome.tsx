import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Play, Sparkles, ArrowUp } from 'lucide-react';
import { useT } from '../i18n';
import { useWorkflows, useExecuteWorkflow } from '../hooks/useWorkflows';
import { useSettingsStore } from '../stores/settingsStore';
import { useAssistantStore } from '../stores/assistantStore';
import { useLatestRunStatuses } from '../hooks/useLatestRunStatuses';
import { buildHomeTiles, greetingKey } from '../utils/homeTiles';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import { readContextSchema } from '../utils/contextSchema';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import { Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { SimpleOnboarding } from '../components/simple/SimpleOnboarding';
import { SimpleOverview } from '../components/simple/SimpleOverview';
import { FeatureTile } from '../components/simple/FeatureTile';
import { StatusDot } from '../components/simple/kit';
import type { Workflow } from '../types';

export function SimpleHome() {
  const t = useT();
  const navigate = useNavigate();
  const { pinnedWorkflowIds } = useSettingsStore();
  const { data: workflows, isLoading, error, refetch } = useWorkflows();
  const executeWorkflow = useExecuteWorkflow();
  const latestStatus = useLatestRunStatuses();
  const setPendingInput = useAssistantStore((s) => s.setPendingInput);
  const setAssistantOpen = useAssistantStore((s) => s.setOpen);

  const [assistantInput, setAssistantInput] = useState('');
  const [executeDialog, setExecuteDialog] = useState<{ open: boolean; workflow: Workflow | null; variables: string[] }>(
    { open: false, workflow: null, variables: [] },
  );

  const { navTiles, runTiles } = useMemo(
    () => buildHomeTiles(workflows ?? [], pinnedWorkflowIds, 4),
    [workflows, pinnedWorkflowIds],
  );
  const greeting = t.simple.home[greetingKey(new Date())];

  const submitAssistant = () => {
    const text = assistantInput.trim();
    setPendingInput(text || null);
    setAssistantOpen(true);
    setAssistantInput('');
  };

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
    <div className="animate-fade-in max-w-4xl mx-auto space-y-6">
      <SimpleOnboarding />

      {/* Greeting */}
      <div>
        <p className="text-sm text-text-secondary mb-1">{greeting}</p>
        <h1 className="text-[28px] font-bold text-text tracking-tight leading-none">{t.simple.home.question}</h1>
      </div>

      {/* Assistant — the hero input */}
      <form
        onSubmit={(e) => { e.preventDefault(); submitAssistant(); }}
        className="relative rounded-2xl p-[1.5px] shadow-[0_18px_50px_-30px_rgba(129,140,248,0.7)]"
        style={{ background: 'linear-gradient(120deg, rgba(129,140,248,.55), rgba(56,189,248,.30) 40%, rgba(167,139,250,.45))' }}
      >
        <div className="flex items-center gap-3 bg-surface-raised rounded-[14px] px-4 py-3">
          <div
            className="w-9 h-9 rounded-xl flex items-center justify-center shrink-0"
            style={{ background: 'linear-gradient(135deg, rgba(129,140,248,.25), rgba(56,189,248,.18))' }}
          >
            <Sparkles className="w-[18px] h-[18px]" style={{ color: '#aab4ff' }} />
          </div>
          <input
            value={assistantInput}
            onChange={(e) => setAssistantInput(e.target.value)}
            placeholder={t.simple.home.assistantPlaceholder}
            className="flex-1 min-w-0 bg-transparent text-sm text-text placeholder:text-text-tertiary focus:outline-none"
          />
          <button
            type="submit"
            aria-label={t.simple.home.assistantLabel}
            className="w-8 h-8 rounded-lg bg-accent text-white flex items-center justify-center shrink-0 hover:opacity-90 transition-opacity"
          >
            <ArrowUp className="w-4 h-4" />
          </button>
        </div>
      </form>

      {/* "How's it looking?" overview */}
      <SimpleOverview />

      {/* Areas — reach the right place */}
      <div>
        <p className="text-[11px] tracking-widest uppercase text-text-tertiary font-semibold mb-3">{t.simple.home.exploreTitle}</p>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
          {navTiles.map((tile) => (
            <FeatureTile
              key={tile.id}
              icon={tile.icon}
              color={tile.color}
              title={t.simple.home.tiles[tile.id].title}
              description={t.simple.home.tiles[tile.id].desc}
              onClick={() => navigate(tile.to)}
            />
          ))}
        </div>
      </div>

      {/* Quick run — compact chips */}
      {runTiles.length > 0 && (
        <div>
          <p className="text-[11px] tracking-widest uppercase text-text-tertiary font-semibold mb-3">{t.simple.home.runTilesTitle}</p>
          <div className="flex flex-wrap gap-2">
            {runTiles.map((w) => {
              const status = latestStatus.get(w.id);
              return (
                <button
                  key={w.id}
                  onClick={() => handleExecute(w)}
                  className="group flex items-center gap-2.5 rounded-xl border border-border bg-surface px-3 py-2 hover:border-accent/40 hover:bg-surface-raised transition-colors"
                >
                  <span className="text-lg leading-none">{w.icon || '⚡'}</span>
                  <span className="text-[13px] font-medium text-text whitespace-nowrap">{w.name}</span>
                  <StatusDot status={status} />
                  <Play className="w-3 h-3 text-text-tertiary group-hover:text-accent transition-colors" />
                </button>
              );
            })}
            <button
              onClick={() => navigate('/workflows')}
              aria-label={t.simple.home.more}
              className="flex items-center justify-center rounded-xl border border-dashed border-border bg-surface px-4 py-2 text-sm tracking-widest text-text-tertiary hover:text-text hover:border-accent/40 transition-colors"
            >
              ···
            </button>
          </div>
        </div>
      )}

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
