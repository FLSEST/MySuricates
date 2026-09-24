import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useT } from '../../i18n';
import { useAllRuns } from '../../hooks/useWorkflows';
import { useRemainingToday } from '../../hooks/useSchedules';
import { useTodoCount } from '../../hooks/useTodos';
import { usePendingInputs } from '../../hooks/usePendingInputs';

const TERMINAL_OK = new Set(['COMPLETED', 'COMPLETED_WITH_ERRORS']);

interface PillProps {
  color: string;
  value: number;
  label: string;
  onClick: () => void;
  /** Frame and text in the pill's role instead of neutral — for what waits on the person. */
  emphasis?: string;
}

function Pill({ color, value, label, onClick, emphasis }: PillProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex items-center gap-2 rounded-full border px-3 py-1.5 text-xs transition-colors ${emphasis ?? 'border-border bg-surface text-text-secondary hover:border-accent/40'}`}
    >
      <span className="w-[7px] h-[7px] rounded-full shrink-0" style={{ background: color }} />
      <span><b className="text-text font-semibold">{value}</b> {label}</span>
    </button>
  );
}

/** Compact "how's it looking?" health row — colored pills, each navigates. Not the Pro dashboard. */
export function SimpleOverview() {
  const t = useT();
  const navigate = useNavigate();
  const { data: runsPage } = useAllRuns({ size: 100 });
  const { data: remaining } = useRemainingToday();
  const { data: todoCount } = useTodoCount();
  const { data: pendingInputs } = usePendingInputs();
  const waiting = pendingInputs?.length ?? 0;

  const today = useMemo(() => {
    const runs = runsPage?.content ?? [];
    const todayStr = new Date().toISOString().slice(0, 10);
    const todays = runs.filter((r) => r.startedAt?.slice(0, 10) === todayStr);
    return {
      green: todays.filter((r) => TERMINAL_OK.has(r.status)).length,
      failed: todays.filter((r) => r.status === 'FAILED').length,
    };
  }, [runsPage]);

  return (
    <div className="flex flex-wrap gap-2">
      {waiting > 0 && (
        <Pill
          color="var(--color-human)"
          value={waiting}
          label={t.simple.home.waitingLabel}
          onClick={() => navigate('/approvals')}
          emphasis="border-human/40 bg-human-subtle text-human hover:border-human"
        />
      )}
      <Pill color="var(--color-success)" value={today.green} label={t.simple.home.ranLabel} onClick={() => navigate('/runs')} />
      <Pill color="var(--color-danger)" value={today.failed} label={t.simple.home.failedLabel} onClick={() => navigate('/runs')} />
      <Pill color="var(--color-ai)" value={remaining?.count ?? 0} label={t.simple.home.scheduledLabel} onClick={() => navigate('/schedules')} />
      <Pill color="var(--color-warning)" value={todoCount?.open ?? 0} label={t.simple.home.tasksLabel} onClick={() => navigate('/todos')} />
    </div>
  );
}
