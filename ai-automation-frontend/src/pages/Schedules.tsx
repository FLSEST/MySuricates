import { useState } from 'react';
import { Calendar, Plus, Trash2, Pencil, List, Link2 } from 'lucide-react';
import { Card, Button, Input, Select, Modal, Spinner, Table, Toggle, CronPicker } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { CalendarView } from '../components/schedule/CalendarView';
import { useSchedules, useCreateSchedule, useUpdateSchedule, useToggleSchedule, useDeleteSchedule } from '../hooks/useSchedules';
import { useWorkflows } from '../hooks/useWorkflows';
import { useChains } from '../hooks/useChains';
import { useT } from '../i18n';
import type { ScheduledWorkflow, CreateScheduleRequest, UpdateScheduleRequest } from '../types';

type TargetType = 'WORKFLOW' | 'CHAIN';

interface FormState extends CreateScheduleRequest {
  targetType: TargetType;
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

  const [view, setView] = useState<'list' | 'calendar'>('list');
  const [showCreate, setShowCreate] = useState(false);
  const [editingSchedule, setEditingSchedule] = useState<ScheduledWorkflow | null>(null);
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
  };

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
    });
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
      };
      if (form.targetType === 'CHAIN') {
        payload.chainId = form.chainId;
      } else {
        payload.workflowId = form.workflowId;
      }
      createSchedule.mutate(payload, { onSuccess: closeModal });
    }
  };

  const isFormValid = (
    form.targetType === 'CHAIN' ? !!form.chainId : !!form.workflowId
  ) && (
    form.scheduleType === 'ONCE' ? !!form.scheduledAt
    : form.scheduleType === 'DELAYED' ? !!form.delayExpression
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
        <span className="text-text-secondary">
          {s.lastRunAt ? new Date(s.lastRunAt).toLocaleString() : '-'}
        </span>
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
          <Table
            columns={columns}
            data={schedules}
            keyExtractor={(s) => s.id}
          />
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
            <Input
              label={t.schedules.delayExpression}
              value={form.delayExpression ?? ''}
              onChange={(e) => setForm({ ...form, delayExpression: e.target.value })}
              placeholder={t.schedules.delayExpressionPlaceholder}
            />
          ) : (
            <CronPicker
              label={t.schedules.cronExpression}
              value={form.cronExpression ?? ''}
              onChange={(cron) => setForm({ ...form, cronExpression: cron })}
            />
          )}

          <Input
            label={t.common.description}
            value={form.description ?? ''}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
            placeholder="Daily code quality check"
          />
        </div>
      </Modal>
    </div>
  );
}
