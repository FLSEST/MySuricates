import { useState, useCallback, useMemo, Fragment } from 'react';
import { Calendar, Plus, Trash2, Pencil, List, Link2, ChevronDown, ChevronRight, History, Pause, Play, AlertTriangle, Search } from 'lucide-react';
import { Card, Button, Spinner, Toggle } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { CalendarView } from '../components/schedule/CalendarView';
import { ScheduleFormModal, type ScheduleFormState } from '../components/schedule/ScheduleFormModal';
import { useSchedules, useCreateSchedule, useUpdateSchedule, useToggleSchedule, useDeleteSchedule, useScheduleRuns, useMaintenanceMode, useSetMaintenanceMode } from '../hooks/useSchedules';
import { useWorkflows } from '../hooks/useWorkflows';
import { useChains } from '../hooks/useChains';
import { usePersistedState } from '../hooks/usePersistedState';
import { useT } from '../i18n';
import type { ScheduledWorkflow, CreateScheduleRequest, UpdateScheduleRequest, RunStatus } from '../types';

const DELAY_PATTERN = /^\d+(s|m|h|d)$/;

function statusDot(status: RunStatus) {
  const colors: Record<string, string> = {
    COMPLETED: 'bg-success',
    COMPLETED_WITH_ERRORS: 'bg-success',
    FAILED: 'bg-danger',
    INTERRUPTED: 'bg-danger',
    RUNNING: 'bg-accent',
    CANCELLED: 'bg-gray-400',
  };
  return <span className={`inline-block w-2 h-2 rounded-full ${colors[status] ?? 'bg-gray-400'}`} />;
}

function ScheduleRunsPanel({ scheduleId }: { scheduleId: string }) {
  const t = useT();
  const { data, isLoading } = useScheduleRuns(scheduleId);

  if (isLoading) {
    return <div className="text-xs text-text-secondary py-1">{t.common.loading}</div>;
  }

  const workflowRuns = data?.workflowRuns ?? [];
  const chainRuns = data?.chainRuns ?? [];
  const totalRuns = workflowRuns.length + chainRuns.length;

  if (totalRuns === 0) {
    return (
      <div className="flex items-center gap-2 text-xs text-text-tertiary py-1">
        <History className="w-3.5 h-3.5" />
        {t.schedules.noRuns}
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-1.5 text-xs font-semibold text-text-secondary uppercase tracking-wider mb-2">
        <History className="w-3.5 h-3.5" />
        {t.schedules.runHistory} ({totalRuns})
      </div>
      <div className="space-y-1">
        {workflowRuns.map((run) => (
          <div key={run.id} className="flex items-center gap-3 text-xs py-1 px-2 rounded bg-surface/60">
            {statusDot(run.status)}
            <span className="text-text-secondary font-medium">{t.schedules.workflowRun}</span>
            <span className="text-text truncate">{run.workflowName}</span>
            {run.projectName && <span className="text-text-tertiary truncate">({run.projectName})</span>}
            <span className="ml-auto text-text-tertiary shrink-0">
              {run.startedAt ? new Date(run.startedAt).toLocaleString() : '-'}
            </span>
            {run.durationMs != null && (
              <span className="text-text-tertiary shrink-0">{(run.durationMs / 1000).toFixed(1)}s</span>
            )}
          </div>
        ))}
        {chainRuns.map((run) => (
          <div key={run.id} className="flex items-center gap-3 text-xs py-1 px-2 rounded bg-surface/60">
            {statusDot(run.status)}
            <span className="text-text-secondary font-medium">{t.schedules.chainRun}</span>
            <span className="text-text truncate">{run.chainName ?? run.chainId}</span>
            <span className="ml-auto text-text-tertiary shrink-0">
              {run.startedAt ? new Date(run.startedAt).toLocaleString() : '-'}
            </span>
            {run.durationMs != null && (
              <span className="text-text-tertiary shrink-0">{(run.durationMs / 1000).toFixed(1)}s</span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

export function Schedules() {
  const t = useT();
  const { data: schedules, isLoading, error, refetch } = useSchedules();
  const { data: workflows } = useWorkflows();
  const { data: chains } = useChains();
  const createSchedule = useCreateSchedule();
  const updateSchedule = useUpdateSchedule();
  const toggleSchedule = useToggleSchedule();
  const deleteSchedule = useDeleteSchedule();
  const { data: maintenanceData } = useMaintenanceMode();
  const setMaintenance = useSetMaintenanceMode();
  const isMaintenanceMode = maintenanceData?.enabled ?? false;

  const [view, setView] = useState<'list' | 'calendar'>('list');
  const [showCreate, setShowCreate] = useState(false);
  const [editingSchedule, setEditingSchedule] = useState<ScheduledWorkflow | null>(null);
  const [showVariables, setShowVariables] = useState(false);
  const [expandedScheduleId, setExpandedScheduleId] = useState<string | null>(null);
  const [showEndConditions, setShowEndConditions] = useState(false);

  // Filters
  const [targetTypeFilter, setTargetTypeFilter] = usePersistedState<'ALL' | 'WORKFLOW' | 'CHAIN'>('schedules-target-filter', 'ALL');
  const [scheduleTypeFilter, setScheduleTypeFilter] = usePersistedState<'ALL' | 'CRON' | 'ONCE' | 'DELAYED'>('schedules-type-filter', 'ALL');
  const [enabledFilter, setEnabledFilter] = usePersistedState<'ALL' | 'ON' | 'OFF'>('schedules-enabled-filter', 'ALL');
  const [projectFilter, setProjectFilter] = usePersistedState('schedules-project-filter', '');
  const [search, setSearch] = usePersistedState('schedules-search', '');
  const [sortBy, setSortBy] = usePersistedState<'nextRun' | 'lastRun' | 'name' | 'created'>('schedules-sort', 'nextRun');

  const uniqueProjects = useMemo(() => {
    if (!schedules) return [];
    return [...new Set(schedules.map(s => s.projectName).filter(Boolean) as string[])].sort();
  }, [schedules]);

  const filtered = useMemo(() => {
    if (!schedules) return [];
    let result = [...schedules];

    if (targetTypeFilter !== 'ALL') result = result.filter(s => s.targetType === targetTypeFilter);
    if (scheduleTypeFilter !== 'ALL') result = result.filter(s => s.scheduleType === scheduleTypeFilter);
    if (enabledFilter === 'ON') result = result.filter(s => s.enabled);
    if (enabledFilter === 'OFF') result = result.filter(s => !s.enabled);
    if (projectFilter) result = result.filter(s => s.projectName === projectFilter);
    if (search.trim()) {
      const q = search.toLowerCase();
      result = result.filter(s =>
        s.workflowName?.toLowerCase().includes(q) ||
        s.chainName?.toLowerCase().includes(q) ||
        s.description?.toLowerCase().includes(q)
      );
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
  }, [schedules, targetTypeFilter, scheduleTypeFilter, enabledFilter, projectFilter, search, sortBy]);
  const [form, setForm] = useState<ScheduleFormState>({
    targetType: 'WORKFLOW',
    workflowId: '',
    chainId: '',
    cronExpression: '',
    scheduleType: 'CRON',
    delayExpression: '',
  });

  const defaultForm: ScheduleFormState = { targetType: 'WORKFLOW', workflowId: '', chainId: '', cronExpression: '', scheduleType: 'CRON', delayExpression: '' };

  const closeModal = () => {
    setShowCreate(false);
    setEditingSchedule(null);
    setForm(defaultForm);
    setShowVariables(false);
    setShowEndConditions(false);
  };

  const setVariable = useCallback((key: string, value: string, oldKey?: string) => {
    setForm((prev) => {
      const ctx = { ...(prev.inputContext ?? {}) } as Record<string, unknown>;
      if (oldKey && oldKey !== key) delete ctx[oldKey];
      ctx[key] = value;
      return { ...prev, inputContext: ctx };
    });
  }, []);

  const removeVariable = useCallback((key: string) => {
    setForm((prev) => {
      const ctx = { ...(prev.inputContext ?? {}) } as Record<string, unknown>;
      delete ctx[key];
      return { ...prev, inputContext: ctx };
    });
  }, []);

  const handleEdit = (schedule: ScheduledWorkflow) => {
    setEditingSchedule(schedule);
    setForm({
      targetType: schedule.targetType,
      workflowId: schedule.workflowId ?? '',
      chainId: schedule.chainId ?? '',
      cronExpression: schedule.cronExpression ?? '',
      description: schedule.description ?? '',
      inputContext: schedule.inputContext,
      scheduleType: schedule.scheduleType ?? 'CRON',
      scheduledAt: schedule.scheduledAt ?? undefined,
      endDate: schedule.endDate ?? null,
      maxRuns: schedule.maxRuns ?? undefined,
      conflictBehavior: schedule.conflictBehavior ?? 'SKIP',
    });
    const hasEndConditions = !!(schedule.endDate || (schedule.maxRuns && schedule.maxRuns > 0));
    setShowEndConditions(hasEndConditions);
    setShowCreate(true);
  };

  const handleSubmit = () => {
    if (editingSchedule) {
      const data: UpdateScheduleRequest = {};
      if (editingSchedule.scheduleType === 'CRON') {
        data.cronExpression = form.cronExpression;
      } else {
        data.scheduledAt = form.scheduledAt;
      }
      data.description = form.description;
      data.inputContext = form.inputContext;
      if (editingSchedule.scheduleType === 'CRON' && showEndConditions) {
        data.endDate = form.endDate ?? null;
        data.maxRuns = form.maxRuns ?? null;
      }
      updateSchedule.mutate({ id: editingSchedule.id, data }, { onSuccess: closeModal });
    } else {
      const payload: CreateScheduleRequest = {
        targetType: form.targetType,
        cronExpression: form.scheduleType === 'CRON' ? form.cronExpression : undefined,
        description: form.description,
        inputContext: form.inputContext,
        scheduleType: form.scheduleType,
        scheduledAt: form.scheduleType === 'ONCE' ? form.scheduledAt : undefined,
        delayExpression: form.scheduleType === 'DELAYED' ? form.delayExpression : undefined,
        endDate: form.scheduleType === 'CRON' && showEndConditions ? (form.endDate ?? null) : null,
        maxRuns: form.scheduleType === 'CRON' && showEndConditions ? (form.maxRuns ?? null) : null,
        conflictBehavior: form.conflictBehavior ?? 'SKIP',
      };
      if (form.targetType === 'CHAIN') {
        payload.chainId = form.chainId;
      } else {
        payload.workflowId = form.workflowId;
      }
      createSchedule.mutate(payload, { onSuccess: closeModal });
    }
  };

  const isDelayValid = !form.delayExpression || DELAY_PATTERN.test(form.delayExpression.trim());

  const isFormValid = (
    form.targetType === 'CHAIN' ? !!form.chainId : !!form.workflowId
  ) && (
    form.scheduleType === 'ONCE' ? !!form.scheduledAt
    : form.scheduleType === 'DELAYED' ? (!!form.delayExpression && isDelayValid)
    : !!form.cronExpression
  );

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  const columns = [
    {
      key: 'workflow',
      header: t.schedules.targetType,
      render: (s: ScheduledWorkflow) => (
        <div className="min-w-0 flex items-center gap-2">
          {s.targetType === 'CHAIN' && (
            <Link2 className="w-3.5 h-3.5 text-accent/70 shrink-0" />
          )}
          <div className="min-w-0">
            <span className="font-medium text-text block truncate">
              {s.targetType === 'CHAIN' ? s.chainName : s.workflowName}
            </span>
            {s.targetType === 'CHAIN' ? (
              <span className="text-xs text-accent/70 block truncate">{t.schedules.targetChain}</span>
            ) : s.projectName ? (
              <span className="text-xs text-accent/70 block truncate">{s.projectName}</span>
            ) : null}
          </div>
        </div>
      ),
    },
    {
      key: 'type',
      header: t.schedules.scheduleType,
      render: (s: ScheduledWorkflow) => (
        <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
          s.scheduleType === 'ONCE'
            ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300'
            : s.scheduleType === 'DELAYED'
            ? 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300'
            : 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'
        }`}>
          {s.scheduleType === 'ONCE' ? t.schedules.once
            : s.scheduleType === 'DELAYED' ? t.schedules.delayed
            : t.schedules.recurring}
        </span>
      ),
      width: '120px',
    },
    {
      key: 'schedule',
      header: t.schedules.cronExpression,
      render: (s: ScheduledWorkflow) => (
        <span className="font-mono text-xs text-text-secondary">
          {s.scheduleType === 'ONCE' || s.scheduleType === 'DELAYED'
            ? (s.scheduledAt ? new Date(s.scheduledAt).toLocaleString() : '-')
            : s.cronExpression}
          {s.scheduleType === 'DELAYED' && s.delayExpression && (
            <span className="ml-1 text-orange-500">({s.delayExpression})</span>
          )}
        </span>
      ),
    },
    {
      key: 'nextRun',
      header: t.schedules.nextRun,
      render: (s: ScheduledWorkflow) => (
        <span className="text-text-secondary">
          {(s.scheduleType === 'ONCE' || s.scheduleType === 'DELAYED') && !s.enabled
            ? (<span className="text-xs px-2 py-0.5 rounded-full bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400">{t.schedules.completed}</span>)
            : s.nextRunAt ? new Date(s.nextRunAt).toLocaleString() : '-'}
        </span>
      ),
    },
    {
      key: 'lastRun',
      header: t.schedules.lastRun,
      render: (s: ScheduledWorkflow) => (
        <div className="flex items-center gap-2">
          {s.lastRunStatus && statusDot(s.lastRunStatus)}
          <span className="text-text-secondary">
            {s.lastRunAt ? new Date(s.lastRunAt).toLocaleString() : '-'}
          </span>
        </div>
      ),
    },
    {
      key: 'enabled',
      header: t.common.status,
      render: (s: ScheduledWorkflow) => (
        <Toggle
          enabled={s.enabled}
          onChange={(enabled) => toggleSchedule.mutate({ id: s.id, enable: enabled })}
        />
      ),
      width: '80px',
    },
    {
      key: 'actions',
      header: '',
      render: (s: ScheduledWorkflow) => (
        <div className="flex items-center gap-1">
          <button
            onClick={(e) => {
              e.stopPropagation();
              handleEdit(s);
            }}
            className="p-1.5 rounded-lg text-text-tertiary hover:text-accent transition-colors"
            aria-label={t.schedules.edit}
          >
            <Pencil className="w-4 h-4" />
          </button>
          <button
            onClick={(e) => {
              e.stopPropagation();
              deleteSchedule.mutate(s.id);
            }}
            className="p-1.5 rounded-lg text-text-tertiary hover:text-danger transition-colors"
            aria-label={t.common.delete}
          >
            <Trash2 className="w-4 h-4" />
          </button>
        </div>
      ),
      width: '80px',
    },
  ];

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.schedules.title}</h1>
          <p className="text-sm text-text-secondary mt-1">{t.schedules.subtitle}</p>
        </div>
        <div className="flex items-center gap-2">
          {/* Maintenance mode toggle */}
          <button
            onClick={() => setMaintenance.mutate(!isMaintenanceMode)}
            className={`p-2 rounded-lg border transition-colors ${
              isMaintenanceMode
                ? 'bg-warning/10 border-warning/30 text-warning hover:bg-warning/20'
                : 'border-border text-text-tertiary hover:text-text hover:bg-surface-secondary'
            }`}
            title={isMaintenanceMode ? t.schedules.resumeScheduler : t.schedules.pauseScheduler}
          >
            {isMaintenanceMode ? <Play className="w-4 h-4" /> : <Pause className="w-4 h-4" />}
          </button>
          {/* View Toggle */}
          <div className="flex items-center bg-surface-secondary rounded-lg p-0.5 border border-border">
            <button
              onClick={() => setView('list')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                view === 'list'
                  ? 'bg-surface text-text shadow-xs'
                  : 'text-text-secondary hover:text-text'
              }`}
            >
              <List className="w-4 h-4" />
              {t.schedules.listView}
            </button>
            <button
              onClick={() => setView('calendar')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                view === 'calendar'
                  ? 'bg-surface text-text shadow-xs'
                  : 'text-text-secondary hover:text-text'
              }`}
            >
              <Calendar className="w-4 h-4" />
              {t.schedules.calendarView}
            </button>
          </div>
          <Button icon={<Plus className="w-4 h-4" />} onClick={() => setShowCreate(true)}>
            {t.schedules.create}
          </Button>
        </div>
      </div>

      {/* Maintenance mode banner */}
      {isMaintenanceMode && (
        <div className="flex items-center gap-3 px-4 py-3 rounded-lg bg-warning/10 border border-warning/30 text-warning">
          <AlertTriangle className="w-4 h-4 shrink-0" />
          <span className="text-sm font-medium">{t.schedules.maintenanceModeActive}</span>
        </div>
      )}

      {/* Filter bar (list view only) */}
      {view === 'list' && schedules && schedules.length > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1">
            {(['ALL', 'WORKFLOW', 'CHAIN'] as const).map(v => (
              <button key={v} onClick={() => setTargetTypeFilter(v)}
                className={`px-2.5 py-1 rounded-lg text-xs font-medium transition-colors ${
                  targetTypeFilter === v ? 'bg-accent text-white' : 'text-text-secondary hover:text-text hover:bg-surface-raised'
                }`}>
                {v === 'ALL' ? t.common.all : v === 'WORKFLOW' ? 'Workflow' : 'Chain'}
              </button>
            ))}
          </div>
          <select className="px-2.5 py-1.5 rounded-lg border border-border bg-surface-raised text-xs"
            value={scheduleTypeFilter} onChange={e => setScheduleTypeFilter(e.target.value as typeof scheduleTypeFilter)}>
            <option value="ALL">{t.schedules.scheduleType}</option>
            <option value="CRON">{t.schedules.recurring}</option>
            <option value="ONCE">{t.schedules.once}</option>
            <option value="DELAYED">{t.schedules.delayed}</option>
          </select>
          <select className="px-2.5 py-1.5 rounded-lg border border-border bg-surface-raised text-xs"
            value={enabledFilter} onChange={e => setEnabledFilter(e.target.value as typeof enabledFilter)}>
            <option value="ALL">{t.common.status}</option>
            <option value="ON">{t.common.enabled}</option>
            <option value="OFF">{t.common.disabled}</option>
          </select>
          {uniqueProjects.length > 0 && (
            <select className="px-2.5 py-1.5 rounded-lg border border-border bg-surface-raised text-xs max-w-[160px]"
              value={projectFilter} onChange={e => setProjectFilter(e.target.value)}>
              <option value="">{t.schedules.allProjects}</option>
              {uniqueProjects.map(p => <option key={p} value={p}>{p}</option>)}
            </select>
          )}
          <select className="px-2.5 py-1.5 rounded-lg border border-border bg-surface-raised text-xs"
            value={sortBy} onChange={e => setSortBy(e.target.value as typeof sortBy)}>
            <option value="nextRun">{t.schedules.nextRun}</option>
            <option value="lastRun">{t.schedules.lastRun}</option>
            <option value="name">{t.common.name}</option>
            <option value="created">{t.common.created}</option>
          </select>
          <div className="relative flex-1 min-w-[140px] max-w-xs">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-text-tertiary pointer-events-none" />
            <input type="text" className="w-full pl-8 pr-3 py-1.5 rounded-lg border border-border bg-surface-raised text-xs placeholder:text-text-tertiary"
              placeholder={t.common.search} value={search} onChange={e => setSearch(e.target.value)} />
          </div>
        </div>
      )}

      {/* Content */}
      {view === 'calendar' ? (
        <CalendarView />
      ) : !schedules?.length ? (
        <EmptyState
          icon={<Calendar className="w-8 h-8 text-text-tertiary" />}
          title={t.schedules.title}
          description={t.schedules.noSchedules}
          action={
            <Button size="sm" onClick={() => setShowCreate(true)}>
              {t.schedules.create}
            </Button>
          }
        />
      ) : (
        <Card padding={false}>
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border">
                  <th className="w-8" />
                  {columns.map((col) => (
                    <th
                      key={col.key}
                      className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-text-tertiary"
                      style={col.width ? { width: col.width } : undefined}
                    >
                      {col.header}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {filtered.length === 0 && (
                  <tr>
                    <td colSpan={columns.length + 1} className="px-4 py-8 text-center text-text-secondary text-sm">
                      {t.common.noResults}
                    </td>
                  </tr>
                )}
                {filtered.map((s) => (
                  <Fragment key={s.id}>
                    <tr className="transition-colors">
                      <td className="pl-3 pr-0 py-3">
                        <button
                          onClick={() => setExpandedScheduleId(expandedScheduleId === s.id ? null : s.id)}
                          className="p-1 rounded text-text-tertiary hover:text-accent transition-colors"
                          aria-label={t.schedules.runHistory}
                        >
                          {expandedScheduleId === s.id
                            ? <ChevronDown className="w-3.5 h-3.5" />
                            : <ChevronRight className="w-3.5 h-3.5" />}
                        </button>
                      </td>
                      {columns.map((col) => (
                        <td key={col.key} className="px-4 py-3 text-sm">
                          {col.render(s)}
                        </td>
                      ))}
                    </tr>
                    {expandedScheduleId === s.id && (
                      <tr className="bg-surface-secondary/50">
                        <td colSpan={columns.length + 1} className="px-6 py-4">
                          <ScheduleRunsPanel scheduleId={s.id} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* Create/Edit modal */}
      <ScheduleFormModal
        open={showCreate}
        editingSchedule={editingSchedule}
        form={form}
        showEndConditions={showEndConditions}
        showVariables={showVariables}
        workflows={workflows}
        chains={chains}
        isLoading={editingSchedule ? updateSchedule.isPending : createSchedule.isPending}
        isFormValid={isFormValid}
        onClose={closeModal}
        onFormChange={setForm}
        onShowEndConditionsChange={setShowEndConditions}
        onShowVariablesChange={setShowVariables}
        onVariableSet={setVariable}
        onVariableRemove={removeVariable}
        onSubmit={handleSubmit}
      />
    </div>
  );
}
