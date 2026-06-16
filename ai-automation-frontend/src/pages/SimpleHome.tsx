import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Play, Search } from 'lucide-react';
import { useT } from '../i18n';
import { useWorkflows, useExecuteWorkflow } from '../hooks/useWorkflows';
import { useSettingsStore } from '../stores/settingsStore';
import { useLatestRunStatuses } from '../hooks/useLatestRunStatuses';
import { matchWorkflows } from '../utils/matchWorkflows';
import { buildHomeTiles, greetingKey } from '../utils/homeTiles';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import { readContextSchema } from '../utils/contextSchema';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import { Spinner, Button } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { SimpleOnboarding } from '../components/simple/SimpleOnboarding';
import { SimpleOverview } from '../components/simple/SimpleOverview';
import { FeatureTile } from '../components/simple/FeatureTile';
import type { Workflow } from '../types';

export function SimpleHome() {
  const t = useT();
  const navigate = useNavigate();
  const { pinnedWorkflowIds } = useSettingsStore();
  const { data: workflows, isLoading, error, refetch } = useWorkflows();
  const executeWorkflow = useExecuteWorkflow();
  const latestStatus = useLatestRunStatuses();

  const [intent, setIntent] = useState('');
  const [executeDialog, setExecuteDialog] = useState<{ open: boolean; workflow: Workflow | null; variables: string[] }>(
    { open: false, workflow: null, variables: [] },
  );

  const matches = useMemo(() => matchWorkflows(intent, workflows ?? []).slice(0, 5), [intent, workflows]);
  const { navTiles, runTiles } = useMemo(
    () => buildHomeTiles(workflows ?? [], pinnedWorkflowIds, 4),
    [workflows, pinnedWorkflowIds],
  );
  const greeting = t.simple.home[greetingKey(new Date())];

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

      <div>
        <p className="text-sm text-text-secondary mb-1">{greeting}</p>
        <h1 className="text-2xl font-bold text-text">{t.simple.home.question}</h1>
      </div>

      {/* Slim search (secondary) */}
      <div>
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-text-tertiary pointer-events-none" />
          <input
            type="search"
            value={intent}
            onChange={(e) => setIntent(e.target.value)}
            placeholder={t.simple.home.searchHint}
            className="w-full pl-9 pr-3 py-2.5 text-sm bg-surface border border-border rounded-lg text-text placeholder:text-text-tertiary focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </div>
        {intent.trim() && matches.length > 0 && (
          <div className="space-y-2 mt-2">
            {matches.map((m) => (
              <div
                key={m.workflow.id}
                className="flex items-center justify-between px-4 py-2.5 rounded-lg bg-surface border border-border hover:border-accent/40 transition-colors cursor-pointer"
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
          <button className="text-sm text-accent hover:underline mt-2" onClick={() => navigate('/workflows')}>
            {t.simple.home.noMatchTitle}
          </button>
        )}
      </div>

      {/* "How's it looking?" overview */}
      <SimpleOverview />

      {/* Feature tiles — reach the right place */}
      <div>
        <p className="text-xs text-text-tertiary mb-2 uppercase tracking-wide">{t.simple.home.exploreTitle}</p>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
          {navTiles.map((tile) => (
            <FeatureTile
              key={tile.id}
              icon={tile.icon}
              title={t.simple.home.tiles[tile.id].title}
              description={t.simple.home.tiles[tile.id].desc}
              onClick={() => navigate(tile.to)}
            />
          ))}
        </div>
      </div>

      {/* Quick run — top workflows */}
      {runTiles.length > 0 && (
        <div>
          <p className="text-xs text-text-tertiary mb-2 uppercase tracking-wide">{t.simple.home.runTilesTitle}</p>
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
            {runTiles.map((w) => (
              <FeatureTile
                key={w.id}
                emoji={w.icon || '⚡'}
                title={w.name}
                description={w.description || undefined}
                status={latestStatus.get(w.id)}
                onClick={() => handleExecute(w)}
              />
            ))}
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
