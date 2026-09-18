import { useT } from '../../i18n';
import { useProjects } from '../../hooks/useProjects';
import { selectActiveView, useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import { TimeRangeSelector } from '../insights/TimeRangeSelector';
import { DASHBOARD_DEFAULT_DAYS } from './dashboardDataContext';
import type { DashboardViewFilter } from './dashboardLayout';

interface DashboardViewFilterBarProps {
  editMode: boolean;
  /** Locks the controls (e.g. while a server version is applied). */
  disabled: boolean;
}

/**
 * Data filter of the active view (spec E4). It belongs to the view, so it is changed in edit mode and
 * saved with the layout; outside edit mode an active filter is only shown.
 */
export function DashboardViewFilterBar({ editMode, disabled }: DashboardViewFilterBarProps) {
  const t = useT();
  const view = useDashboardLayoutStore(selectActiveView);
  const setViewFilter = useDashboardLayoutStore((s) => s.setViewFilter);
  const { data: projects } = useProjects();

  if (!view) return null;
  const filter: DashboardViewFilter = view.filter ?? {};
  const days = filter.days ?? DASHBOARD_DEFAULT_DAYS;
  const selectableProjects = projects?.filter((p) => !p.archivedAt) ?? [];
  const selectedProject = filter.projectId ? projects?.find((p) => p.id === filter.projectId) : undefined;

  if (!editMode) {
    if (!view.filter) return null;
    // While the projects load, leave the project out rather than calling it unknown.
    const projectLabel = filter.projectId && projects ? selectedProject?.name ?? t.dashboard.filter.unknownProject : null;
    const parts = [projectLabel, t.dashboard.filter.days.replace('{days}', () => String(days))].filter(Boolean);
    return (
      <p data-testid="dashboard-view-filter" className="text-xs text-text-tertiary">
        {t.dashboard.filter.active} {parts.join(' · ')}
      </p>
    );
  }

  const update = (next: DashboardViewFilter) => setViewFilter(view.id, next);

  return (
    <div
      data-testid="dashboard-view-filter"
      role="group"
      aria-label={t.dashboard.filter.label}
      className="flex flex-wrap items-center gap-3 rounded-xl border border-border bg-surface-raised px-4 py-3"
    >
      <select
        aria-label={t.dashboard.filter.project}
        disabled={disabled}
        value={filter.projectId ?? ''}
        onChange={(e) => update({ ...filter, projectId: e.target.value || undefined })}
        className="max-w-full min-w-0 rounded-lg border border-border bg-surface px-2.5 py-1.5 text-xs text-text-secondary disabled:cursor-not-allowed disabled:opacity-50"
      >
        <option value="">{t.dashboard.filter.allProjects}</option>
        {filter.projectId && !selectableProjects.some((p) => p.id === filter.projectId) && (
          <option value={filter.projectId}>{selectedProject?.name ?? t.dashboard.filter.unknownProject}</option>
        )}
        {selectableProjects.map((p) => (
          <option key={p.id} value={p.id}>{p.name}</option>
        ))}
      </select>
      <TimeRangeSelector
        value={days}
        disabled={disabled}
        onChange={(next) => update({ ...filter, days: next === DASHBOARD_DEFAULT_DAYS ? undefined : next })}
      />
      <p className="basis-full text-xs text-text-tertiary">{t.dashboard.filter.hint}</p>
    </div>
  );
}
