import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, Calendar, CheckSquare, Webhook } from 'lucide-react';
import { Card, CardHeader, Badge, Spinner } from '../ui';
import { useT, useI18nStore } from '../../i18n';
import { useSchedulesForWorkflow } from '../../hooks/useSchedules';
import { useWebhooksForWorkflow } from '../../hooks/useWebhooks';
import { useTodoTriggersForWorkflow } from '../../hooks/useTodoTriggers';
import { useCompletionTriggersAsTarget } from '../../hooks/useCompletionTriggers';
import { useModuleEnabled } from '../../hooks/useModules';
import { formatDateTime, formatTimeUntil } from '../../utils/formatTime';
import type { ScheduledWorkflow } from '../../types';

interface Props {
  workflowId: string;
  conditionLabel: (triggerOnSuccess: boolean, triggerOnFailure: boolean, triggerAlways: boolean) => string;
}

/**
 * Everything that starts this workflow on its own: schedules, webhooks, TODO triggers
 * and completion triggers of other workflows. Chains and sub-workflow blocks are not
 * listed — they reference the workflow from their own definition.
 */
export function IncomingTriggersCard({ workflowId, conditionLabel }: Props) {
  const t = useT();
  const it = t.incomingTriggers;
  const locale = useI18nStore((s) => s.locale);
  const webhooksEnabled = useModuleEnabled('webhooks');

  const schedules = useSchedulesForWorkflow(workflowId);
  const webhooks = useWebhooksForWorkflow(workflowId, webhooksEnabled);
  const todoTriggers = useTodoTriggersForWorkflow(workflowId);
  const completion = useCompletionTriggersAsTarget(workflowId);

  const scheduleList = schedules.data ?? [];
  const webhookList = webhooksEnabled ? webhooks.data ?? [] : [];
  const todoTriggerList = todoTriggers.data ?? [];
  const completionList = completion.data ?? [];

  const loading = schedules.isLoading || (webhooksEnabled && webhooks.isLoading)
    || todoTriggers.isLoading || completion.isLoading;
  const empty = scheduleList.length + webhookList.length + todoTriggerList.length + completionList.length === 0;

  function scheduleLabel(s: ScheduledWorkflow) {
    if (s.description) return s.description;
    if (s.scheduleType === 'ONCE' && s.scheduledAt) return formatDateTime(s.scheduledAt, locale);
    if (s.scheduleType === 'DELAYED' && s.delayExpression) return s.delayExpression;
    return s.cronExpression;
  }

  return (
    <Card padding={false}>
      <div className="p-5 pb-0">
        <CardHeader title={t.completionTriggers.asTarget} />
      </div>
      <div className="p-5 pt-0 space-y-4" data-testid="incoming-triggers">
        {loading ? (
          <div className="flex justify-center py-4"><Spinner /></div>
        ) : empty ? (
          <p className="text-sm text-text-tertiary py-2">{it.none}</p>
        ) : (
          <>
            {scheduleList.length > 0 && (
              <Group icon={<Calendar className="w-3.5 h-3.5" />} title={it.schedules} to="/schedules" testId="incoming-schedules">
                {scheduleList.map((s) => (
                  <Row key={s.id} enabled={s.enabled} inactiveLabel={it.inactive}>
                    <span className={`text-sm text-text truncate ${s.description ? '' : 'font-mono'}`}>{scheduleLabel(s)}</span>
                    {s.enabled && s.nextRunAt && (
                      <span className="text-xs text-text-tertiary shrink-0">
                        {it.nextRun} {formatTimeUntil(s.nextRunAt, locale)}
                      </span>
                    )}
                  </Row>
                ))}
              </Group>
            )}

            {webhookList.length > 0 && (
              <Group icon={<Webhook className="w-3.5 h-3.5" />} title={it.webhooks} to="/webhooks" testId="incoming-webhooks">
                {webhookList.map((w) => (
                  <Row key={w.id} enabled={w.enabled} inactiveLabel={it.inactive}>
                    <span className="text-sm text-text truncate">{w.description || it.webhookUnnamed}</span>
                    {w.triggerCount > 0 && (
                      <span className="text-xs text-text-tertiary shrink-0">{w.triggerCount}×</span>
                    )}
                  </Row>
                ))}
              </Group>
            )}

            {todoTriggerList.length > 0 && (
              <Group icon={<CheckSquare className="w-3.5 h-3.5" />} title={it.todoTriggers} to="/todos" testId="incoming-todo-triggers">
                {todoTriggerList.map((tt) => (
                  <Row key={tt.id} enabled={tt.enabled} inactiveLabel={it.inactive}>
                    <span className="text-sm text-text truncate">
                      {tt.description || `${it.tag} ${tt.tagPattern}`}
                    </span>
                    {tt.minPriority && <Badge>≥ {tt.minPriority}</Badge>}
                    {tt.triggerCount > 0 && (
                      <span className="text-xs text-text-tertiary shrink-0">{tt.triggerCount}×</span>
                    )}
                  </Row>
                ))}
              </Group>
            )}

            {completionList.length > 0 && (
              <Group icon={<ArrowLeft className="w-3.5 h-3.5" />} title={it.workflows} testId="incoming-workflows">
                {completionList.map((c) => (
                  <Row key={c.id} enabled={c.enabled} inactiveLabel={it.inactive}>
                    <Link to={`/workflows/${c.sourceWorkflowId}`} className="text-sm text-text truncate hover:text-accent">
                      {c.sourceWorkflowName}
                    </Link>
                    <Badge>{conditionLabel(c.triggerOnSuccess, c.triggerOnFailure, c.triggerAlways)}</Badge>
                  </Row>
                ))}
              </Group>
            )}
          </>
        )}
        <p className="text-xs text-text-tertiary">{it.notListed}</p>
      </div>
    </Card>
  );
}

function Group({ icon, title, to, testId, children }: {
  icon: ReactNode; title: string; to?: string; testId: string; children: ReactNode;
}) {
  return (
    <div data-testid={testId}>
      <div className="flex items-center gap-1.5 text-xs font-medium text-text-secondary mb-1">
        {icon}
        {to ? <Link to={to} className="hover:text-accent">{title}</Link> : <span>{title}</span>}
      </div>
      <div className="space-y-1 pl-5">{children}</div>
    </div>
  );
}

function Row({ enabled, inactiveLabel, children }: { enabled: boolean; inactiveLabel: string; children: ReactNode }) {
  return (
    // Everything after the label (badges, counters) keeps its width; only the label truncates.
    <div className={`flex items-center gap-2 py-0.5 min-w-0 [&>*:not(:first-child)]:shrink-0 [&>*]:whitespace-nowrap ${enabled ? '' : 'opacity-60'}`}>
      {children}
      {!enabled && <Badge>{inactiveLabel}</Badge>}
    </div>
  );
}
