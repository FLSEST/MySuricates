import { useNavigate } from 'react-router-dom';
import { useSystemHealth } from '../../hooks/useSystem';
import { useT } from '../../i18n';

interface Props {
  collapsed: boolean;
}

export function HealthIndicator({ collapsed }: Props) {
  const { data: health, isLoading } = useSystemHealth();
  const navigate = useNavigate();
  const t = useT();

  let color: string;
  let label: string;

  if (isLoading || !health) {
    color = 'bg-gray-500';
    label = '...';
  } else if (health.status === 'UP') {
    color = 'bg-emerald-400';
    label = t.health.allSystemsOk;
  } else if (health.status === 'DEGRADED') {
    color = 'bg-amber-400';
    label = health.components?.docker?.status === 'DOWN'
      ? t.health.dockerOffline
      : t.health.degraded;
  } else {
    color = 'bg-red-500';
    label = health.components?.database?.status === 'DOWN'
      ? t.health.databaseDown
      : t.health.down;
  }

  return (
    <button
      onClick={() => navigate('/settings')}
      className="flex w-full items-center gap-2.5 rounded-lg px-3 py-1.5 text-xs text-[var(--color-sidebar-text-muted)] transition-colors hover:bg-[var(--color-sidebar-hover)] hover:text-[var(--color-sidebar-text)]"
      title={label}
    >
      <span className={`w-2 h-2 rounded-full shrink-0 ${color}`} />
      {!collapsed && <span className="truncate">{label}</span>}
    </button>
  );
}
