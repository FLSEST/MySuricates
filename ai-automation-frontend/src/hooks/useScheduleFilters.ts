import { useMemo } from 'react';
import { usePersistedState } from './usePersistedState';
import type { ScheduledWorkflow } from '../types';

export type TargetTypeFilter = 'ALL' | 'WORKFLOW' | 'CHAIN';
export type ScheduleTypeFilter = 'ALL' | 'CRON' | 'ONCE' | 'DELAYED';
export type EnabledFilter = 'ALL' | 'ON' | 'OFF';
export type ScheduleSort = 'nextRun' | 'lastRun' | 'name' | 'created';

/**
 * Persisted filter/sort state for the schedules list plus the derived
 * (filtered + sorted) rows and the distinct project names for the dropdown.
 * `pinnedId` (the schedule a link points to, `?schedule=`, 3a35ced1) stays in the rows whatever the
 * persisted filters say — otherwise a forgotten filter would hide exactly the linked schedule.
 */
export function useScheduleFilters(schedules: ScheduledWorkflow[] | undefined, pinnedId?: string | null) {
  const [targetTypeFilter, setTargetTypeFilter] = usePersistedState<TargetTypeFilter>('schedules-target-filter', 'ALL');
  const [scheduleTypeFilter, setScheduleTypeFilter] = usePersistedState<ScheduleTypeFilter>('schedules-type-filter', 'ALL');
  const [enabledFilter, setEnabledFilter] = usePersistedState<EnabledFilter>('schedules-enabled-filter', 'ALL');
  const [projectFilter, setProjectFilter] = usePersistedState('schedules-project-filter', '');
  const [search, setSearch] = usePersistedState('schedules-search', '');
  const [sortBy, setSortBy] = usePersistedState<ScheduleSort>('schedules-sort', 'nextRun');

  const uniqueProjects = useMemo(() => {
    if (!schedules) return [];
    return [...new Set(schedules.map(s => s.projectName).filter(Boolean) as string[])].sort();
  }, [schedules]);

  const filtered = useMemo(() => {
    if (!schedules) return [];
    let result = [...schedules];
    const keep = (match: (s: ScheduledWorkflow) => boolean) => (s: ScheduledWorkflow) => s.id === pinnedId || match(s);

    if (targetTypeFilter !== 'ALL') result = result.filter(keep(s => s.targetType === targetTypeFilter));
    if (scheduleTypeFilter !== 'ALL') result = result.filter(keep(s => s.scheduleType === scheduleTypeFilter));
    if (enabledFilter === 'ON') result = result.filter(keep(s => s.enabled));
    if (enabledFilter === 'OFF') result = result.filter(keep(s => !s.enabled));
    if (projectFilter) result = result.filter(keep(s => s.projectName === projectFilter));
    if (search.trim()) {
      const q = search.toLowerCase();
      result = result.filter(keep(s =>
        s.workflowName?.toLowerCase().includes(q) ||
        s.chainName?.toLowerCase().includes(q) ||
        s.description?.toLowerCase().includes(q)
      ));
    }

    result.sort((a, b) => {
      switch (sortBy) {
        case 'nextRun': return (a.nextRunAt ?? '').localeCompare(b.nextRunAt ?? '');
        case 'lastRun': return (b.lastRunAt ?? '').localeCompare(a.lastRunAt ?? '');
        case 'name': return (a.workflowName ?? a.chainName ?? '').localeCompare(b.workflowName ?? b.chainName ?? '');
        case 'created': return b.createdAt.localeCompare(a.createdAt);
        default: return 0;
      }
    });

    return result;
  }, [schedules, pinnedId, targetTypeFilter, scheduleTypeFilter, enabledFilter, projectFilter, search, sortBy]);

  return {
    targetTypeFilter, setTargetTypeFilter,
    scheduleTypeFilter, setScheduleTypeFilter,
    enabledFilter, setEnabledFilter,
    projectFilter, setProjectFilter,
    search, setSearch,
    sortBy, setSortBy,
    uniqueProjects,
    filtered,
  };
}
