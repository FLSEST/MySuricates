import { useState } from 'react';
import { Calendar, Plus, Trash2 } from 'lucide-react';
import { Card, Button, Input, Select, Modal, Spinner, Table, Toggle } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
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

  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<CreateScheduleRequest>({
    workflowId: '',
    cronExpression: '',
  });

  const handleCreate = () => {
    createSchedule.mutate(form, {
      onSuccess: () => {
        setShowCreate(false);
        setForm({ workflowId: '', cronExpression: '' });
      },
    });
  };

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
      key: 'cron',
      header: t.schedules.cronExpression,
      render: (s: ScheduledWorkflow) => (
        <span className="font-mono text-xs text-text-secondary">{s.cronExpression}</span>
      ),
    },
    {
      key: 'nextRun',
      header: t.schedules.nextRun,
      render: (s: ScheduledWorkflow) => (
        <span className="text-text-secondary">
          {s.nextRunAt ? new Date(s.nextRunAt).toLocaleString() : '-'}
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
        <Button icon={<Plus className="w-4 h-4" />} onClick={() => setShowCreate(true)}>
          {t.schedules.create}
        </Button>
      </div>

      {/* Table */}
      {!schedules?.length ? (
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
              disabled={!form.workflowId || !form.cronExpression}
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
          <Input
            label={t.schedules.cronExpression}
            value={form.cronExpression}
            onChange={(e) => setForm({ ...form, cronExpression: e.target.value })}
            placeholder="0 0 * * *"
            hint={t.schedules.cronHint}
          />
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
