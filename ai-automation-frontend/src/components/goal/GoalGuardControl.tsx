import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { Clock, Play, Workflow } from 'lucide-react';
import { Button, Input, Modal, Toggle } from '../ui';
import { Hint } from '../hints/Hint';
import { useI18nStore, useT } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import { useModuleEnabled } from '../../hooks/useModules';
import { useProjects } from '../../hooks/useProjects';
import { useExecuteWorkflow } from '../../hooks/useWorkflows';
import { useSchedule } from '../../hooks/useSchedules';
import {
  cronWithTime, dailyCron, isDailyCron, timeOfCron, useSetGoalGuard, useSetGuardTime,
} from '../../hooks/useGoalGuard';
import type { Goal, GoalProjectProgress } from '../../types';

const DEFAULT_TIME = '08:00';

/** Vorschlag für das Projekt des Wächters: das mit den meisten TODOs des Ziels – so wählt auch das Backend. */
function busiestProject(byProject: GoalProjectProgress[]): string | null {
  let best: GoalProjectProgress | null = null;
  for (const entry of byProject) {
    if (entry.projectId && (!best || entry.total > best.total)) best = entry;
  }
  return best?.projectId ?? null;
}

/**
 * Schalter „Wächter“ im Kopf der Zielseite (TODO e192df18): ein Workflow aus der Vorlage „Ziel-Wächter“
 * prüft das Ziel täglich und meldet eine Ampel als Dashboard-Kachel. Beim ersten Einschalten fragt ein
 * kleiner Dialog nach Projekt und Uhrzeit, danach zeigt die Zeile den nächsten Lauf, den Workflow,
 * „Uhrzeit ändern“ (TODO ff6f08fd) und „Jetzt prüfen“. Ausschalten pausiert nur den Zeitplan. Ohne das
 * Modul `dashboard-report` gibt es den Schalter nicht – außer ein Wächter besteht schon und soll sich
 * abschalten lassen.
 */
export function GoalGuardControl({ goal, byProject }: { goal: Goal; byProject: GoalProjectProgress[] }) {
  const t = useT();
  const g = t.goals.guard;
  const locale = useI18nStore((s) => s.locale);
  const reportModule = useModuleEnabled('dashboard-report');
  const setGuard = useSetGoalGuard();
  const setTime = useSetGuardTime();
  const execute = useExecuteWorkflow();
  const [asking, setAsking] = useState(false);
  const [editingTime, setEditingTime] = useState(false);
  const { guard } = goal;
  // Uhrzeit nur, wenn der Zeitplan eine feste hat; wer ihn unter „Zeitpläne“ anders gestellt hat, ändert ihn dort
  const { data: schedule } = useSchedule(guard.scheduleId);
  const cron = schedule?.cronExpression ?? null;
  const canChangeTime = !!guard.scheduleId && !!cron && timeOfCron(cron) !== null;
  if (!reportModule && !guard.workflowId) return null;

  const toggle = (on: boolean) => {
    if (!on) setGuard.mutate({ id: goal.id, request: { enabled: false } });
    else if (!guard.workflowId) setAsking(true);
    else setGuard.mutate({ id: goal.id, request: { enabled: true } });
  };
  const switchOn = (projectId: string, cron: string) =>
    setGuard.mutate({ id: goal.id, request: { enabled: true, projectId, cron, locale } }, { onSuccess: () => setAsking(false) });

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-xs text-text-secondary" data-testid="goal-guard">
      <Toggle enabled={guard.enabled || asking} onChange={toggle} label={g.label} ariaLabel={g.label}
        disabled={setGuard.isPending || asking} />
      {guard.enabled && guard.nextRunAt && (
        <span>
          {g.nextRun.replace('{date}', formatTimestamp(guard.nextRunAt, locale, {
            weekday: 'short', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
          }))}
        </span>
      )}
      {!guard.enabled && guard.workflowId && <span className="rounded bg-surface px-1.5 py-0.5">{g.paused}</span>}
      {guard.workflowId && (
        <Link to={`/workflows/${guard.workflowId}`} className="inline-flex items-center gap-1 hover:text-accent">
          <Workflow className="h-3.5 w-3.5" aria-hidden="true" />
          {g.workflow}
        </Link>
      )}
      {canChangeTime && (
        <Button variant="ghost" size="sm" icon={<Clock className="h-3.5 w-3.5" />} onClick={() => setEditingTime(true)}>
          {g.changeTime}
        </Button>
      )}
      {guard.enabled && guard.workflowId && (
        <Button variant="secondary" size="sm" icon={<Play className="h-3.5 w-3.5" />} loading={execute.isPending}
          onClick={() => execute.mutate({ id: guard.workflowId! })}>
          {g.checkNow}
        </Button>
      )}
      {asking && (
        <GuardDialog byProject={byProject} pending={setGuard.isPending} onCancel={() => setAsking(false)} onConfirm={switchOn} />
      )}
      {editingTime && canChangeTime && (
        <GuardTimeDialog cron={cron} pending={setTime.isPending} onCancel={() => setEditingTime(false)}
          onSave={(next) => setTime.mutate({ scheduleId: guard.scheduleId!, cron: next }, { onSuccess: () => setEditingTime(false) })} />
      )}
    </div>
  );
}

/** Uhrzeit des Wächters ändern: nur die Uhrzeit seines Zeitplans, Tage und Wochentage bleiben. */
function GuardTimeDialog({ cron, pending, onCancel, onSave }: {
  cron: string;
  pending: boolean;
  onCancel: () => void;
  onSave: (cron: string) => void;
}) {
  const g = useT().goals.guard;
  const [time, setTime] = useState(() => timeOfCron(cron) ?? DEFAULT_TIME);
  const next = cronWithTime(cron, time);
  const daily = isDailyCron(cron);

  return (
    <Modal open onClose={onCancel} title={g.timeDialogTitle} size="sm" footer={(
      <>
        <Button variant="secondary" onClick={onCancel}>{g.cancel}</Button>
        <Button onClick={() => next && onSave(next)} loading={pending} disabled={!next || next === cron}>
          {g.timeSave}
        </Button>
      </>
    )}>
      <div className="space-y-2">
        <Input label={daily ? g.time : g.timeOfDay} type="time" value={time} onChange={(event) => setTime(event.target.value)} />
        {!daily && <p className="text-xs text-text-tertiary">{g.timeDaysKept}</p>}
      </div>
    </Modal>
  );
}

/** Erstes Einschalten: Projekt (vorbelegt) und Uhrzeit des täglichen Laufs. */
function GuardDialog({ byProject, pending, onCancel, onConfirm }: {
  byProject: GoalProjectProgress[];
  pending: boolean;
  onCancel: () => void;
  onConfirm: (projectId: string, cron: string) => void;
}) {
  const g = useT().goals.guard;
  const projectFieldId = useId();
  const { data: projects = [] } = useProjects();
  const [chosen, setChosen] = useState<string | null>(null);
  const [time, setTime] = useState(DEFAULT_TIME);
  const suggested = busiestProject(byProject);
  const projectId = chosen ?? (projects.some((p) => p.id === suggested) ? suggested : projects[0]?.id) ?? '';
  const cron = dailyCron(time);

  return (
    <Modal open onClose={onCancel} title={g.dialogTitle} size="sm" footer={(
      <>
        <Button variant="secondary" onClick={onCancel}>{g.cancel}</Button>
        <Button onClick={() => cron && onConfirm(projectId, cron)} loading={pending} disabled={!projectId || !cron}>
          {g.enable}
        </Button>
      </>
    )}>
      <div className="space-y-4">
        <p className="text-sm text-text-secondary">{g.intro}</p>
        <Hint id="goal-guard" />
        <div className="space-y-1.5">
          <label htmlFor={projectFieldId} className="block text-sm font-medium text-text-secondary">{g.project}</label>
          <select id={projectFieldId} value={projectId} onChange={(event) => setChosen(event.target.value)}
            className="input-base w-full cursor-pointer">
            {projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
          </select>
          <p className="text-xs text-text-tertiary">{projects.length > 0 ? g.projectHint : g.noProjects}</p>
        </div>
        <Input label={g.time} type="time" value={time} onChange={(event) => setTime(event.target.value)} />
      </div>
    </Modal>
  );
}
