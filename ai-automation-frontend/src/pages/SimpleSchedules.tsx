import { useState } from 'react';
import { Trash2, Plus } from 'lucide-react';
import { useT, useI18nStore } from '../i18n';
import { useSchedules, useToggleSchedule, useDeleteSchedule, useCreateSchedule } from '../hooks/useSchedules';
import { useWorkflows } from '../hooks/useWorkflows';
import { Spinner, Toggle, Button, Modal, Select, Input } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { SimplePage, SimplePageHeader, SimpleRow, StatusDot } from '../components/simple/kit';
import { AREA_COLORS } from '../utils/homeTiles';
import { formatDateTime } from '../utils/formatTime';

// Spring cron has six fields: second minute hour day month weekday.
const DEFAULT_CRON = '0 0 9 * * *';

export function SimpleSchedules() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: schedules, isLoading, error, refetch } = useSchedules();
  const { data: workflows } = useWorkflows();
  const toggle = useToggleSchedule();
  const del = useDeleteSchedule();
  const create = useCreateSchedule();

  const [createOpen, setCreateOpen] = useState(false);
  const [wfId, setWfId] = useState('');
  const [cron, setCron] = useState(DEFAULT_CRON);

  const submitCreate = () => {
    if (!wfId || !cron.trim()) return;
    create.mutate(
      { workflowId: wfId, targetType: 'WORKFLOW', scheduleType: 'CRON', cronExpression: cron.trim() },
      { onSuccess: () => { setCreateOpen(false); setWfId(''); setCron(DEFAULT_CRON); } },
    );
  };

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <SimplePage>
      <SimplePageHeader
        title={t.simple.schedules.title}
        subtitle={t.simple.schedules.subtitle}
        right={<Button size="sm" icon={<Plus className="w-4 h-4" />} onClick={() => setCreateOpen(true)}>{t.common.create}</Button>}
      />

      {(schedules?.length ?? 0) === 0 ? (
        <EmptyState title={t.simple.schedules.empty} description={t.simple.schedules.emptyHint} />
      ) : (
        <div className="space-y-2">
          {(schedules ?? []).map((s) => (
            <SimpleRow key={s.id} color={AREA_COLORS.schedules}>
              <div className="flex items-center gap-3 min-w-0">
                <StatusDot status={s.lastRunStatus ?? undefined} />
                <div className="min-w-0">
                  <p className="text-sm font-medium text-text truncate">{s.workflowName ?? s.chainName ?? '—'}</p>
                  <p className="text-xs text-text-tertiary truncate">
                    {s.scheduleType} · {t.simple.schedules.nextRun}: {s.nextRunAt ? formatDateTime(s.nextRunAt, locale) : '—'}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2 shrink-0">
                <Toggle enabled={s.enabled} onChange={(enable) => toggle.mutate({ id: s.id, enable })} />
                <button
                  aria-label={t.common.delete}
                  className="text-text-tertiary hover:text-danger transition-colors"
                  onClick={() => { if (window.confirm(t.common.deleteConfirm)) del.mutate(s.id); }}
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            </SimpleRow>
          ))}
        </div>
      )}

      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title={t.simple.schedules.createTitle}
        footer={
          <>
            <Button variant="secondary" onClick={() => setCreateOpen(false)}>{t.common.cancel}</Button>
            <Button onClick={submitCreate} loading={create.isPending} disabled={!wfId || !cron.trim()}>{t.common.create}</Button>
          </>
        }
      >
        <div className="space-y-4">
          <Select
            label={t.common.workflow}
            value={wfId}
            onChange={(e) => setWfId(e.target.value)}
            options={[{ value: '', label: t.common.selectWorkflow }, ...(workflows ?? []).map((w) => ({ value: w.id, label: w.name }))]}
          />
          <Input
            label={t.simple.schedules.cron}
            value={cron}
            onChange={(e) => setCron(e.target.value)}
            hint={t.simple.schedules.cronHint}
          />
        </div>
      </Modal>
    </SimplePage>
  );
}
