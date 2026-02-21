import { useState } from 'react';
import { Calendar, Plus, Trash2, List } from 'lucide-react';
import { Card, Button, Input, Select, Modal, Spinner, Table, Toggle, CronPicker } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { CalendarView } from '../components/schedule/CalendarView';
import { useSchedules, useCreateSchedule, useToggleSchedule, useDeleteSchedule } from '../hooks/useSchedules';
import { useWorkflows } from '../hooks/useWorkflows';
import { useT } from '../i18n';
import type { ScheduledWorkflow, CreateScheduleRequest } from '../types';

export function Schedules() {
  const t = useT();
  const { data: schedules, isLoading, error, refetch } = useSchedules();
  const { data: workflows } = useWorkflows();
  const createSchedule = useCreateSchedule();
  const toggleSchedule = useToggleSchedule();
  const deleteSchedule = useDeleteSchedule();

  const [view, setView] = useState<'list' | 'calendar'>('list');
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<CreateScheduleRequest>({
    workflowId: '',
    cronExpression: '',
    scheduleType: 'CRON',
  });

  const handleCreate = () => {
    const payload: CreateScheduleRequest = {
      ...form,
      cronExpression: form.scheduleType === 'ONCE' ? undefined : form.cronExpression,
    };
    createSchedule.mutate(payload, {
      onSuccess: () => {
        setShowCreate(false);
        setForm({ workflowId: '', cronExpression: '', scheduleType: 'CRON' });
      },
    });
  };

  const isFormValid = form.workflowId && (
    form.scheduleType === 'ONCE' ? !!form.scheduledAt : !!form.cronExpression
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
      header: t.common.workflow,
      render: (s: ScheduledWorkflow) => (
        <span className="font-medium text-text">{s.workflowName}</span>
      ),
    },
    {
      key: 'type',
      header: t.schedules.scheduleType,
      render: (s: ScheduledWorkflow) => (
        <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
          s.scheduleType === 'ONCE'
            ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300'
            : 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'
        }`}>
          {s.scheduleType === 'ONCE' ? t.schedules.once : t.schedules.recurring}
        </span>
      ),
      width: '120px',
    },
    {
      key: 'schedule',
      header: t.schedules.cronExpression,
      render: (s: ScheduledWorkflow) => (
        <span className="font-mono text-xs text-text-secondary">
          {s.scheduleType === 'ONCE'
            ? (s.scheduledAt ? new Date(s.scheduledAt).toLocaleString() : '-')
            : s.cronExpression}
        </span>
      ),
    },
    {
      key: 'nextRun',
      header: t.schedules.nextRun,
      render: (s: ScheduledWorkflow) => (
        <span className="text-text-secondary">
          {s.scheduleType === 'ONCE' && !s.enabled
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
      ),
      width: '48px',
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
                  ? 'bg-surface text-text shadow-sm'
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
                  ? 'bg-surface text-text shadow-sm'
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

      {/* Create modal */}
      <Modal
        open={showCreate}
        onClose={() => setShowCreate(false)}
        title={t.schedules.create}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowCreate(false)}>
              {t.common.cancel}
            </Button>
            <Button
              onClick={handleCreate}
              loading={createSchedule.isPending}
              disabled={!isFormValid}
            >
              {t.common.create}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Select
            label={t.common.workflow}
            value={form.workflowId}
            onChange={(e) => setForm({ ...form, workflowId: e.target.value })}
            options={[
              { value: '', label: t.common.selectWorkflow },
              ...(workflows?.map((w) => ({ value: w.id, label: w.name })) ?? []),
            ]}
          />

          {/* Schedule Type Toggle */}
          <div>
            <label className="block text-sm font-medium text-text mb-1.5">{t.schedules.scheduleType}</label>
            <div className="flex items-center bg-surface-secondary rounded-lg p-0.5 border border-border">
              <button
                type="button"
                onClick={() => setForm({ ...form, scheduleType: 'CRON', scheduledAt: undefined })}
                className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                  form.scheduleType !== 'ONCE'
                    ? 'bg-surface text-text shadow-sm'
                    : 'text-text-secondary hover:text-text'
                }`}
              >
                {t.schedules.recurring}
              </button>
              <button
                type="button"
                onClick={() => setForm({ ...form, scheduleType: 'ONCE', cronExpression: '' })}
                className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                  form.scheduleType === 'ONCE'
                    ? 'bg-surface text-text shadow-sm'
                    : 'text-text-secondary hover:text-text'
                }`}
              >
                {t.schedules.once}
              </button>
            </div>
          </div>

          {/* Cron or DateTime picker */}
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
                className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm focus:outline-none focus:ring-2 focus:ring-accent/50"
              />
            </div>
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
