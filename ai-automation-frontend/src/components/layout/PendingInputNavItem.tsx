import { NavLink } from 'react-router-dom';
import { Hand } from 'lucide-react';
import { usePendingInputs } from '../../hooks/usePendingInputs';
import { useT } from '../../i18n';

interface PendingInputNavItemProps {
  showLabels: boolean;
  onNavigate?: () => void;
}

/**
 * Pinned above the regular navigation, and only there while a run waits for an answer: count plus a
 * pulsing dot in the `human` role colour. Outside the sortable/hideable list on purpose — hiding it
 * would silence exactly the signal it exists for. No motion under prefers-reduced-motion.
 */
export function PendingInputNavItem({ showLabels, onNavigate }: PendingInputNavItemProps) {
  const t = useT();
  const { data } = usePendingInputs();
  const count = data?.length ?? 0;
  if (count === 0) return null;

  const label = `${t.nav.approvals}: ${t.approvals.navWaiting.replace('{count}', String(count))}`;

  return (
    <NavLink
      to="/approvals"
      aria-label={label}
      title={label}
      onClick={onNavigate}
      className={({ isActive }) =>
        `relative flex items-center gap-3 px-3 py-2 mb-1.5 rounded-lg text-sm font-semibold transition-colors text-human
         bg-human-subtle hover:brightness-110 ${isActive ? 'ring-1 ring-human' : ''}`
      }
    >
      <span className="relative shrink-0">
        <Hand style={{ width: 18, height: 18 }} />
        <PendingPulse />
      </span>
      {showLabels && <span className="truncate">{t.nav.approvals}</span>}
      {showLabels && (
        <span className="ml-auto min-w-5 rounded-full border border-current bg-surface-raised px-1.5 text-center text-xs font-bold">
          {count}
        </span>
      )}
    </NavLink>
  );
}

/** The pulsing dot that marks a waiting answer — on the sidebar entry and on the mobile menu button. Place inside a `relative` parent. */
export function PendingPulse() {
  return (
    <span className="absolute -top-0.5 -right-0.5 flex h-2.5 w-2.5" aria-hidden="true">
      <span className="absolute inline-flex h-full w-full rounded-full bg-human opacity-75 animate-ping motion-reduce:animate-none" />
      <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-human" />
    </span>
  );
}
