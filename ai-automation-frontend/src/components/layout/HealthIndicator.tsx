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

  // Status tokens, not fixed palette classes, so the dot follows the theme (6c94dc62)
  let color: string;
  let label: string;

  if (isLoading || !health) {
    color = 'bg-text-tertiary';
    label = '...';
  } else if (health.status === 'UP') {
    color = 'bg-success';
    label = t.health.allSystemsOk;
  } else if (health.status === 'DEGRADED') {
    color = 'bg-warning';
    label = health.components?.docker?.status === 'DOWN'
      ? t.health.dockerOffline
      : t.health.degraded;
  } else {
    color = 'bg-danger';
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
