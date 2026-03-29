import { useState, useCallback } from 'react';
import { Calendar, Plus, Trash2, Pencil, List, Link2, X, ChevronDown, ChevronRight, History, Pause, Play, AlertTriangle } from 'lucide-react';
import { Card, Button, Input, Select, Modal, Spinner, Toggle, CronPicker } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { CalendarView } from '../components/schedule/CalendarView';
import { useSchedules, useCreateSchedule, useUpdateSchedule, useToggleSchedule, useDeleteSchedule, useScheduleRuns, useMaintenanceMode, useSetMaintenanceMode } from '../hooks/useSchedules';
import { useWorkflows } from '../hooks/useWorkflows';
import { useChains } from '../hooks/useChains';
import { useT } from '../i18n';
import type { ScheduledWorkflow, CreateScheduleRequest, UpdateScheduleRequest, RunStatus } from '../types';

type TargetType = 'WORKFLOW' | 'CHAIN';

const DELAY_PATTERN = /^\d+(s|m|h|d)$/;

function resolveDelayExpression(expr: string): Date | null {
  const match = expr.trim().match(/^(\d+)(s|m|h|d)$/);
  if (!match) return null;
  const value = parseInt(match[1]!, 10);
  const unit = match[2]!;
  const now = Date.now();
  const ms = unit === 's' ? value * 1000
    : unit === 'm' ? value * 60_000
    : unit === 'h' ? value * 3_600_000
    : value * 86_400_000;
  return new Date(now + ms);
}

interface FormState extends CreateScheduleRequest {
  targetType: TargetType;
}

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
  const [form, setForm] = useState<FormState>({
    targetType: 'WORKFLOW',
    workflowId: '',
    chainId: '',
    cronExpression: '',
    scheduleType: 'CRON',
    delayExpression: '',
  });

  const defaultForm: FormState = { targetType: 'WORKFLOW', workflowId: '', chainId: '', cronExpression: '', scheduleType: 'CRON', delayExpression: '' };

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
                {schedules.map((s) => (
                  <>
                    <tr key={s.id} className="transition-colors">
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
                      <tr key={`${s.id}-runs`} className="bg-surface-secondary/50">
                        <td colSpan={columns.length + 1} className="px-6 py-4">
                          <ScheduleRunsPanel scheduleId={s.id} />
                        </td>
                      </tr>
                    )}
                  </>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* Create/Edit modal */}
      <Modal
        open={showCreate}
        onClose={closeModal}
        title={editingSchedule ? t.schedules.edit : t.schedules.create}
        footer={
          <>
            <Button variant="secondary" onClick={closeModal}>
              {t.common.cancel}
            </Button>
            <Button
              onClick={handleSubmit}
              loading={editingSchedule ? updateSchedule.isPending : createSchedule.isPending}
              disabled={!isFormValid}
            >
              {editingSchedule ? t.common.save : t.common.create}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          {/* Target Type Toggle */}
          <div>
            <label className="block text-sm font-medium text-text mb-1.5">{t.schedules.targetType}</label>
            {editingSchedule ? (
              <div className="px-3 py-2 rounded-lg border border-border bg-surface-secondary text-text text-sm">
                {form.targetType === 'CHAIN'
                  ? (editingSchedule.chainName ?? t.schedules.targetChain)
                  : (editingSchedule.workflowName ?? t.schedules.targetWorkflow)}
              </div>
            ) : (
              <div className="flex items-center bg-surface-secondary rounded-lg p-0.5 border border-border">
                <button
                  type="button"
                  onClick={() => setForm({ ...form, targetType: 'WORKFLOW', chainId: '' })}
                  className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                    form.targetType === 'WORKFLOW'
                      ? 'bg-surface text-text shadow-xs'
                      : 'text-text-secondary hover:text-text'
                  }`}
                >
                  {t.schedules.targetWorkflow}
                </button>
                <button
                  type="button"
                  onClick={() => setForm({ ...form, targetType: 'CHAIN', workflowId: '' })}
                  className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                    form.targetType === 'CHAIN'
                      ? 'bg-surface text-text shadow-xs'
                      : 'text-text-secondary hover:text-text'
                  }`}
                >
                  {t.schedules.targetChain}
                </button>
              </div>
            )}
          </div>

          {/* Workflow or Chain selector (only in create mode) */}
          {!editingSchedule && (
            form.targetType === 'CHAIN' ? (
              <Select
                label={t.schedules.targetChain}
                value={form.chainId ?? ''}
                onChange={(e) => setForm({ ...form, chainId: e.target.value })}
                options={[
                  { value: '', label: t.schedules.selectChain },
                  ...(chains?.map((c) => ({
                    value: c.id,
                    label: c.name,
                  })) ?? []),
                ]}
              />
            ) : (
              <Select
                label={t.common.workflow}
                value={form.workflowId ?? ''}
                onChange={(e) => setForm({ ...form, workflowId: e.target.value })}
                options={[
                  { value: '', label: t.common.selectWorkflow },
                  ...(workflows?.map((w) => ({
                    value: w.id,
                    label: w.projectName ? `${w.name} (${w.projectName})` : w.name,
                  })) ?? []),
                ]}
              />
            )
          )}

          {/* Schedule Type Toggle */}
          <div>
            <label className="block text-sm font-medium text-text mb-1.5">{t.schedules.scheduleType}</label>
            {editingSchedule ? (
              <div className="px-3 py-2 rounded-lg border border-border bg-surface-secondary text-text text-sm">
                {form.scheduleType === 'ONCE' ? t.schedules.once
                  : form.scheduleType === 'DELAYED' ? t.schedules.delayed
                  : t.schedules.recurring}
              </div>
            ) : (
              <div className="flex items-center bg-surface-secondary rounded-lg p-0.5 border border-border">
                <button
                  type="button"
                  onClick={() => setForm({ ...form, scheduleType: 'CRON', scheduledAt: undefined, delayExpression: '' })}
                  className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                    form.scheduleType === 'CRON'
                      ? 'bg-surface text-text shadow-xs'
                      : 'text-text-secondary hover:text-text'
                  }`}
                >
                  {t.schedules.recurring}
                </button>
                <button
                  type="button"
                  onClick={() => setForm({ ...form, scheduleType: 'ONCE', cronExpression: '', delayExpression: '' })}
                  className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                    form.scheduleType === 'ONCE'
                      ? 'bg-surface text-text shadow-xs'
                      : 'text-text-secondary hover:text-text'
                  }`}
                >
                  {t.schedules.once}
                </button>
                <button
                  type="button"
                  onClick={() => setForm({ ...form, scheduleType: 'DELAYED', cronExpression: '', scheduledAt: undefined })}
                  className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                    form.scheduleType === 'DELAYED'
                      ? 'bg-surface text-text shadow-xs'
                      : 'text-text-secondary hover:text-text'
                  }`}
                >
                  {t.schedules.delayed}
                </button>
              </div>
            )}
          </div>

          {/* Cron, DateTime or Delay picker */}
          {form.scheduleType === 'ONCE' ? (
            <div>
              <label className="block text-sm font-medium text-text mb-1.5">{t.schedules.scheduledAt}</label>
              <input
                type="datetime-local"
                value={form.scheduledAt ? form.scheduledAt.slice(0, 16) : ''}
                onChange={(e) => {
                  const val = e.target.value;
                  setForm({ ...form, scheduledAt: val ? new Date(val).toISOString() : undefined });
                }}
                className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm focus:outline-hidden focus:ring-2 focus:ring-accent/50"
              />
            </div>
          ) : form.scheduleType === 'DELAYED' ? (
            <div>
              <Input
                label={t.schedules.delayExpression}
                value={form.delayExpression ?? ''}
                onChange={(e) => setForm({ ...form, delayExpression: e.target.value })}
                placeholder={t.schedules.delayExpressionPlaceholder}
              />
              {form.delayExpression && !isDelayValid && (
                <p className="mt-1 text-xs text-danger">{t.schedules.delayExpressionInvalid}</p>
              )}
              {form.delayExpression && isDelayValid && (() => {
                const resolved = resolveDelayExpression(form.delayExpression);
                if (!resolved) return null;
                const timeStr = resolved.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
                return (
                  <p className="mt-1 text-xs text-text-secondary">
                    {t.schedules.delayExpressionResolved
                      .replace('{delay}', form.delayExpression)
                      .replace('{time}', timeStr)}
                  </p>
                );
              })()}
            </div>
          ) : (
            <>
              <CronPicker
                label={t.schedules.cronExpression}
                value={form.cronExpression ?? ''}
                onChange={(cron) => setForm({ ...form, cronExpression: cron })}
              />
              {/* End conditions — only for CRON */}
              <div className="border border-border rounded-lg overflow-hidden">
                <label className="flex items-center gap-3 px-3 py-2.5 cursor-pointer hover:bg-surface-secondary transition-colors">
                  <input
                    type="checkbox"
                    checked={showEndConditions}
                    onChange={(e) => {
                      setShowEndConditions(e.target.checked);
                      if (!e.target.checked) {
                        setForm({ ...form, endDate: null, maxRuns: undefined });
                      }
                    }}
                    className="w-4 h-4 accent-accent"
                  />
                  <span className="text-sm font-medium text-text">{t.schedules.limitExecutions}</span>
                </label>
                {showEndConditions && (
                  <div className="px-3 pb-3 space-y-3 border-t border-border pt-3">
                    <div>
                      <label className="block text-xs font-medium text-text-secondary mb-1">{t.schedules.endDate}</label>
                      <input
                        type="datetime-local"
                        value={form.endDate ? new Date(form.endDate).toISOString().slice(0, 16) : ''}
                        onChange={(e) => setForm({ ...form, endDate: e.target.value ? new Date(e.target.value).toISOString() : null })}
                        className="w-full px-3 py-2 text-sm bg-surface border border-border rounded-lg text-text focus:outline-none focus:ring-1 focus:ring-accent"
                      />
                      <p className="mt-1 text-xs text-text-tertiary">{t.schedules.endDateHint}</p>
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-text-secondary mb-1">{t.schedules.maxRuns}</label>
                      <Input
                        type="number"
                        min={1}
                        placeholder={t.schedules.maxRunsPlaceholder}
                        value={form.maxRuns != null ? String(form.maxRuns) : ''}
                        onChange={(e) => setForm({ ...form, maxRuns: e.target.value ? parseInt(e.target.value) : undefined })}
                      />
                      <p className="mt-1 text-xs text-text-tertiary">{t.schedules.maxRunsHint}</p>
                    </div>
                  </div>
                )}
              </div>
            </>
          )}

          <Input
            label={t.common.description}
            value={form.description ?? ''}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
            placeholder="Daily code quality check"
          />

          <Select
            label={t.schedules.conflictBehavior}
            value={form.conflictBehavior ?? 'SKIP'}
            onChange={(e) => setForm({ ...form, conflictBehavior: e.target.value as 'SKIP' | 'OVERLAP' })}
            options={[
              { value: 'SKIP', label: t.schedules.conflictBehaviorSkip },
              { value: 'OVERLAP', label: t.schedules.conflictBehaviorOverlap },
            ]}
          />

          {/* Variables accordion */}
          <div className="border border-border rounded-lg overflow-hidden">
            <button
              type="button"
              onClick={() => setShowVariables((v) => !v)}
              className="w-full flex items-center justify-between px-3 py-2.5 text-sm font-medium text-text hover:bg-surface-secondary transition-colors"
            >
              <span className="flex items-center gap-2">
                {t.schedules.variables}
                {Object.keys(form.inputContext ?? {}).length > 0 && (
                  <span className="px-1.5 py-0.5 rounded-full bg-accent/10 text-accent text-xs font-medium">
                    {Object.keys(form.inputContext ?? {}).length}
                  </span>
                )}
              </span>
              <ChevronDown className={`w-4 h-4 text-text-secondary transition-transform ${showVariables ? 'rotate-180' : ''}`} />
            </button>
            {showVariables && (
              <div className="px-3 pb-3 space-y-2 border-t border-border">
                {Object.entries(form.inputContext ?? {}).map(([k, v]) => (
                  <div key={k} className="flex items-center gap-2 pt-2">
                    <Input
                      placeholder={t.schedules.variableKey}
                      value={k}
                      onChange={(e) => setVariable(e.target.value, String(v), k)}
                      className="flex-1"
                    />
                    <Input
                      placeholder={t.schedules.variableValue}
                      value={String(v)}
                      onChange={(e) => setVariable(k, e.target.value)}
                      className="flex-1"
                    />
                    <button
                      type="button"
                      onClick={() => removeVariable(k)}
                      className="p-1.5 text-text-tertiary hover:text-danger transition-colors shrink-0"
                    >
                      <X className="w-4 h-4" />
                    </button>
                  </div>
                ))}
                <button
                  type="button"
                  onClick={() => setVariable(`var${Object.keys(form.inputContext ?? {}).length + 1}`, '')}
                  className="mt-2 flex items-center gap-1.5 text-xs text-accent hover:text-accent/80 transition-colors"
                >
                  <Plus className="w-3.5 h-3.5" />
                  {t.schedules.addVariable}
                </button>
              </div>
            )}
          </div>
        </div>
      </Modal>
    </div>
  );
}
