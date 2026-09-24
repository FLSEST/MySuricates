import { Hint } from '../components/hints/Hint';
import { RefusedStartsNotice } from '../components/workflow/RefusedStartsNotice';
import { WorkflowsTab } from '../components/runs/WorkflowsTab';
import { ChainsTab } from '../components/runs/ChainsTab';
import { useAllRuns, useWorkflows } from '../hooks/useWorkflows';
import { useProjects } from '../hooks/useProjects';
import { useAllChainRuns } from '../hooks/useChains';
import { useModuleEnabled } from '../hooks/useModules';
import { useRunsFilters } from '../hooks/useRunsFilters';
import { useT } from '../i18n';

export function Runs() {
  const t = useT();
  const chainsModule = useModuleEnabled('chains');
  const filters = useRunsFilters(chainsModule);
  const { viewMode } = filters;

  // Both lists load regardless of the tab, so switching tabs shows data at once
  const runs = useAllRuns(filters.workflowQuery);
  const { data: workflows } = useWorkflows();
  const { data: projects } = useProjects();
  const chainRuns = useAllChainRuns(filters.chainQuery);

  const tabClass = (active: boolean) =>
    `px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
      active
        ? 'border-accent text-accent'
        : 'border-transparent text-text-secondary hover:text-text hover:border-border'
    }`;

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-text">{t.runs.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{chainsModule ? t.runs.subtitle : t.runs.subtitleWorkflowsOnly}</p>
      </div>

      <Hint id="runs-list" />

      <RefusedStartsNotice />

      {/* Tabs */}
      <div className="flex border-b border-border">
        <button className={tabClass(viewMode === 'workflows')} onClick={() => filters.changeTab('workflows')}>
          {t.runs.tabWorkflows}
        </button>
        {chainsModule && (
          <button className={tabClass(viewMode === 'chains')} onClick={() => filters.changeTab('chains')}>
            {t.runs.tabChains}
          </button>
        )}
      </div>

      {viewMode === 'workflows' ? (
        <WorkflowsTab
          filters={filters.workflow}
          statusOptions={filters.statusOptions}
          workflows={workflows}
          projects={projects}
          runs={runs}
        />
      ) : (
        <ChainsTab filters={filters.chain} statusOptions={filters.statusOptions} chainRuns={chainRuns} />
      )}
    </div>
  );
}
