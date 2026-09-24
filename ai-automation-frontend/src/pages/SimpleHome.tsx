import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { GitBranch } from 'lucide-react';
import { useT } from '../i18n';
import { useWorkflows } from '../hooks/useWorkflows';
import { useSettingsStore } from '../stores/settingsStore';
import { useAssistantStore } from '../stores/assistantStore';
import { useLatestRunStatuses } from '../hooks/useLatestRunStatuses';
import { useModuleEnabled, useSystemModules } from '../hooks/useModules';
import { AREA_COLORS, buildHomeTiles, greetingKey } from '../utils/homeTiles';
import { Button, Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { Hint } from '../components/hints/Hint';
import { AiProviderStartCard } from '../components/dashboard/AiProviderStartCard';
import { SimpleOverview } from '../components/simple/SimpleOverview';
import { FeatureTile } from '../components/simple/FeatureTile';
import { ProBadge } from '../components/simple/ProHandoff';
import { simpleRouteKind } from '../utils/simpleRoutes';
import { RunChip, SimpleCard } from '../components/simple/kit';
import { HomeCommand } from '../components/simple/home-command/HomeCommand';
import { FirstProjectActions } from '../components/project/FirstProjectActions';
import { useProjects } from '../hooks/useProjects';
import { useSimpleRun } from '../components/simple/useSimpleRun';

export function SimpleHome() {
  const t = useT();
  const navigate = useNavigate();
  const { pinnedWorkflowIds } = useSettingsStore();
  const { data: workflows, isLoading, error, refetch } = useWorkflows();
  const { data: projects } = useProjects();
  const simpleRun = useSimpleRun();
  const latestStatus = useLatestRunStatuses();
  const setPendingInput = useAssistantStore((s) => s.setPendingInput);
  const setAssistantOpen = useAssistantStore((s) => s.setOpen);
  const assistantModule = useModuleEnabled('assistant');
  const { data: systemModules } = useSystemModules();

  const { navTiles, runTiles } = useMemo(() => {
    const disabled = new Set((systemModules?.modules ?? []).filter((m) => !m.enabled).map((m) => m.name));
    return buildHomeTiles(workflows ?? [], pinnedWorkflowIds, 4, disabled);
  }, [workflows, pinnedWorkflowIds, systemModules]);
  const greeting = t.simple.home[greetingKey(new Date())];
  const hasWorkflows = (workflows?.length ?? 0) > 0;

  // Until the answer shows up in place (Stage B), asking opens the assistant panel with the text
  const askAssistant = (text: string) => {
    setPendingInput(text || null);
    setAssistantOpen(true);
  };

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <div className="animate-fade-in max-w-4xl mx-auto space-y-6">
      <Hint id="simple-home" />

      <AiProviderStartCard />

      {/* Greeting */}
      <div>
        <p className="text-sm text-text-secondary mb-1">{greeting}</p>
        <h1 className="text-[28px] font-bold text-text tracking-tight leading-none">{t.simple.home.question}</h1>
      </div>

      {/* First start — offers that depend on what exists, not a tour */}
      {projects?.length === 0 ? (
        <SimpleCard color={AREA_COLORS.projects} className="space-y-3">
          <div>
            <p className="text-sm font-semibold text-text">{t.projects.firstProject.title}</p>
            <p className="text-sm text-text-secondary mt-1">{t.projects.firstProject.workflowsHint}</p>
          </div>
          <FirstProjectActions />
        </SimpleCard>
      ) : projects && projects.length > 0 && !hasWorkflows ? (
        <SimpleCard color={AREA_COLORS.workflows} className="space-y-3">
          <div>
            <p className="text-sm font-semibold text-text">{t.simple.home.firstWorkflowTitle}</p>
            <p className="text-sm text-text-secondary mt-1">{t.simple.home.firstWorkflowHint}</p>
          </div>
          <Button size="sm" icon={<GitBranch className="w-4 h-4" />} onClick={() => navigate('/workflows?new=1')}>
            {t.simple.home.startFromTemplate}
          </Button>
        </SimpleCard>
      ) : null}

      {/* The hero: instant hits from every source; with the assistant module Enter also asks it */}
      <HomeCommand onAsk={assistantModule ? askAssistant : undefined} />

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
              badge={simpleRouteKind(tile.to) === 'pro' ? <ProBadge /> : undefined}
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
            {runTiles.map((w) => (
              <RunChip key={w.id} workflow={w} status={latestStatus.get(w.id)} onRun={() => simpleRun.run(w)} />
            ))}
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

      {simpleRun.dialog}
    </div>
  );
}
