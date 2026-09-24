import { Hint } from '../components/hints/Hint';
import { useState, Fragment } from 'react';
import { Calendar, Plus, List, ChevronDown, ChevronRight, Pause, Play, AlertTriangle, Search } from 'lucide-react';
import { Card, Button, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { CalendarView } from '../components/schedule/CalendarView';
import { ScheduleFormModal } from '../components/schedule/ScheduleFormModal';
import { ScheduleRunsPanel } from '../components/schedule/ScheduleRunsPanel';
import { useScheduleColumns } from '../components/schedule/scheduleColumns';
import { CatchUpQueueBanner } from '../components/schedule/CatchUpQueueBanner';
import { SkipUntilBanner } from '../components/schedule/SkipUntilBanner';
import { useSchedules, useToggleSchedule, useDeleteSchedule, useMaintenanceMode, useSetMaintenanceMode, useCatchUpQueue } from '../hooks/useSchedules';
import { useScheduleFilters } from '../hooks/useScheduleFilters';
import { useScheduleForm } from '../hooks/useScheduleForm';
import { useWorkflows } from '../hooks/useWorkflows';
import { useChains } from '../hooks/useChains';
import { useModuleEnabled } from '../hooks/useModules';
import { useExecutionConfig } from '../hooks/useSystem';
import { useT } from '../i18n';

export function Schedules() {
  const t = useT();
  const { data: schedules, isLoading, error, refetch } = useSchedules();
  const { data: workflows } = useWorkflows();
  const { data: chains } = useChains();
  const chainsModule = useModuleEnabled('chains');
  const toggleSchedule = useToggleSchedule();
  const deleteSchedule = useDeleteSchedule();
  const { data: maintenanceData } = useMaintenanceMode();
  const { data: catchUpQueue } = useCatchUpQueue();
  const setMaintenance = useSetMaintenanceMode();
  const isMaintenanceMode = maintenanceData?.enabled ?? false;
  const { data: executionConfig } = useExecutionConfig();
  const globalCatchUpOnResume = executionConfig?.schedulerCatchUpOnResume === undefined
    ? undefined
    : executionConfig.schedulerCatchUpOnResume === 1;

  const [view, setView] = useState<'list' | 'calendar'>('list');
  const [expandedScheduleId, setExpandedScheduleId] = useState<string | null>(null);

  const filters = useScheduleFilters(schedules);
  const scheduleForm = useScheduleForm();

  const columns = useScheduleColumns({
    onEdit: scheduleForm.handleEdit,
    onDelete: (id) => deleteSchedule.mutate(id),
    onToggle: (id, enable) => toggleSchedule.mutate({ id, enable }),
  });

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

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.schedules.title}</h1>
          <p className="text-sm text-text-secondary mt-1">{chainsModule ? t.schedules.subtitle : t.schedules.subtitleWorkflowsOnly}</p>
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
          <Button icon={<Plus className="w-4 h-4" />} onClick={scheduleForm.openCreate}>
            {t.schedules.create}
          </Button>
        </div>
      </div>

      <Hint id="schedules-timing" />

      {/* Maintenance mode banner */}
      {isMaintenanceMode && (
        <div className="flex items-center gap-3 px-4 py-3 rounded-lg bg-warning/10 border border-warning/30 text-warning">
          <AlertTriangle className="w-4 h-4 shrink-0" />
          <span className="text-sm font-medium">{t.schedules.maintenanceModeActive}</span>
        </div>
      )}

      {/* Skip-until pause from the configuration: due schedules are skipped until its end */}
      <SkipUntilBanner pausedUntil={maintenanceData?.pausedUntil} />

      {/* Sequential catch-up after resume / cold start */}
      <CatchUpQueueBanner queue={catchUpQueue} />


      {/* Filter bar (list view only) */}
      {view === 'list' && schedules && schedules.length > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1">
            {(chainsModule ? (['ALL', 'WORKFLOW', 'CHAIN'] as const) : (['ALL', 'WORKFLOW'] as const)).map(v => (
              <button key={v} onClick={() => filters.setTargetTypeFilter(v)}
                className={`px-2.5 py-1 rounded-lg text-xs font-medium transition-colors ${
                  filters.targetTypeFilter === v ? 'bg-accent text-text-inverse' : 'text-text-secondary hover:text-text hover:bg-surface-raised'
                }`}>
                {v === 'ALL' ? t.common.all : v === 'WORKFLOW' ? 'Workflow' : 'Chain'}
              </button>
            ))}
          </div>
          <select className="px-2.5 py-1.5 rounded-lg border border-border bg-surface-raised text-xs"
            value={filters.scheduleTypeFilter} onChange={e => filters.setScheduleTypeFilter(e.target.value as typeof filters.scheduleTypeFilter)}>
            <option value="ALL">{t.schedules.scheduleType}</option>
            <option value="CRON">{t.schedules.recurring}</option>
            <option value="ONCE">{t.schedules.once}</option>
            <option value="DELAYED">{t.schedules.delayed}</option>
          </select>
          <select className="px-2.5 py-1.5 rounded-lg border border-border bg-surface-raised text-xs"
            value={filters.enabledFilter} onChange={e => filters.setEnabledFilter(e.target.value as typeof filters.enabledFilter)}>
            <option value="ALL">{t.common.status}</option>
            <option value="ON">{t.common.enabled}</option>
            <option value="OFF">{t.common.disabled}</option>
          </select>
          {filters.uniqueProjects.length > 0 && (
            <select className="px-2.5 py-1.5 rounded-lg border border-border bg-surface-raised text-xs max-w-[160px]"
              value={filters.projectFilter} onChange={e => filters.setProjectFilter(e.target.value)}>
              <option value="">{t.schedules.allProjects}</option>
              {filters.uniqueProjects.map(p => <option key={p} value={p}>{p}</option>)}
            </select>
          )}
          <select className="px-2.5 py-1.5 rounded-lg border border-border bg-surface-raised text-xs"
            value={filters.sortBy} onChange={e => filters.setSortBy(e.target.value as typeof filters.sortBy)}>
            <option value="nextRun">{t.schedules.nextRun}</option>
            <option value="lastRun">{t.schedules.lastRun}</option>
            <option value="name">{t.common.name}</option>
            <option value="created">{t.common.created}</option>
          </select>
          <div className="relative flex-1 min-w-[140px] max-w-xs">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-text-tertiary pointer-events-none" />
            <input type="text" className="w-full pl-8 pr-3 py-1.5 rounded-lg border border-border bg-surface-raised text-xs placeholder:text-text-tertiary"
              placeholder={t.common.search} value={filters.search} onChange={e => filters.setSearch(e.target.value)} />
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
            <Button size="sm" onClick={scheduleForm.openCreate}>
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
                {filters.filtered.length === 0 && (
                  <tr>
                    <td colSpan={columns.length + 1} className="px-4 py-8 text-center text-text-secondary text-sm">
                      {t.common.noResults}
                    </td>
                  </tr>
                )}
                {filters.filtered.map((s) => (
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
        open={scheduleForm.showCreate}
        editingSchedule={scheduleForm.editingSchedule}
        form={scheduleForm.form}
        showEndConditions={scheduleForm.showEndConditions}
        showVariables={scheduleForm.showVariables}
        workflows={workflows}
        chains={chains}
        chainsEnabled={chainsModule}
        globalCatchUpOnResume={globalCatchUpOnResume}
        isLoading={scheduleForm.isSubmitting}
        isFormValid={scheduleForm.isFormValid}
        onClose={scheduleForm.closeModal}
        onFormChange={scheduleForm.setForm}
        onShowEndConditionsChange={scheduleForm.setShowEndConditions}
        onShowVariablesChange={scheduleForm.setShowVariables}
        onVariableSet={scheduleForm.setVariable}
        onVariableRemove={scheduleForm.removeVariable}
        onSubmit={scheduleForm.handleSubmit}
      />
    </div>
  );
}
