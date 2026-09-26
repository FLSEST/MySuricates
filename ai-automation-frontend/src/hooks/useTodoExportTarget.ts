import { useSyncedState } from './useSyncedState';
import { useTaskTargets } from './useIntegrations';
import type { Todo, Project, IntegrationProviderInfo } from '../types';

interface TodoExportTargetInput {
  todo: Todo;
  /** Project chosen in the form; falls back to the todo's own project. */
  projectId?: string;
  projects: Project[];
  integrations: IntegrationProviderInfo[];
}

/**
 * Where a todo would be exported to: the connected task trackers, the chosen (or the project's
 * configured, or the first) tracker and target, and the tracker a todo was already exported to.
 * The own choice starts empty for every todo that is opened (reset when the todo id changes).
 */
export function useTodoExportTarget({ todo, projectId, projects, integrations }: TodoExportTargetInput) {
  const [exportProvider, setExportProvider] = useSyncedState(todo.id, () => '');
  const [exportTarget, setExportTarget] = useSyncedState(todo.id, () => '');

  const taskSinks = integrations.filter((p) => p.supportsTaskExport && p.status === 'CONNECTED');
  const exportProject = projects.find((p) => p.id === (projectId ?? todo.projectId ?? ''));
  const exportCfg = (exportProject?.config?.taskExport ?? {}) as { provider?: string; target?: string };
  const activeProvider = exportProvider || exportCfg.provider || taskSinks[0]?.providerId || '';
  const activeTarget = (exportTarget.trim() || exportCfg.target || '').trim();
  const { data: taskTargets } = useTaskTargets(activeProvider, !todo.exportedProvider && taskSinks.length > 0);
  const activeSink = taskSinks.find((p) => p.providerId === activeProvider);
  const exported = integrations.find((p) => p.providerId === todo.exportedProvider);

  return {
    taskSinks,
    taskTargets,
    activeProvider,
    activeProviderName: activeSink?.displayName ?? activeProvider,
    activeProviderIcon: activeSink?.icon,
    activeTarget,
    /** Free-text target as typed (the select shows `activeTarget`). */
    exportTarget,
    configuredTarget: exportCfg.target,
    setExportProvider,
    setExportTarget,
    exportedProviderName: exported?.displayName ?? todo.exportedProvider ?? '',
    exportedProviderIcon: exported?.icon,
  };
}
