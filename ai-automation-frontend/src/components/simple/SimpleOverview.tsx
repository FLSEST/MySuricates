import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useT } from '../../i18n';
import { useAllRuns } from '../../hooks/useWorkflows';
import { useRemainingToday } from '../../hooks/useSchedules';
import { useTodoCount } from '../../hooks/useTodos';
import { Badge } from '../ui';

const TERMINAL_OK = new Set(['COMPLETED', 'COMPLETED_WITH_ERRORS']);

interface ChipProps {
  label: string;
  onClick: () => void;
  children: React.ReactNode;
}

function Chip({ label, onClick, children }: ChipProps) {
  return (
    <button
      onClick={onClick}
      className="flex items-center justify-between gap-2 rounded-xl border border-border bg-surface px-4 py-3 text-left hover:border-accent/40 transition-colors"
    >
      <span className="text-xs text-text-tertiary truncate">{label}</span>
      <span className="flex items-center gap-1 shrink-0">{children}</span>
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
    <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
      <Chip label={t.simple.home.todayPrefix} onClick={() => navigate('/runs')}>
        <Badge variant="success" dot>{today.green}</Badge>
        <Badge variant="danger" dot>{today.failed}</Badge>
      </Chip>
      <Chip label={t.simple.home.plannedToday} onClick={() => navigate('/schedules')}>
        <Badge variant="accent">{remaining?.count ?? 0}</Badge>
      </Chip>
      <Chip label={t.simple.home.openTodos} onClick={() => navigate('/todos')}>
        <Badge variant="warning">{todoCount?.open ?? 0}</Badge>
      </Chip>
    </div>
  );
}
