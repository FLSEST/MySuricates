import { Lock, LockOpen } from 'lucide-react';
import type { ReactNode } from 'react';
import type { TranslationKeys } from '../../i18n';

export type SealFilter = 'all' | 'sealed' | 'unsealed';

/**
 * Filter the workflow list by seal state. Always offered, with counts — also when nothing is sealed
 * yet, so that "not sealed" is a visible state and not just the absence of a lock.
 */
export function SealFilterTabs({ t, value, onChange, sealedCount, unsealedCount }: {
  t: TranslationKeys;
  value: SealFilter;
  onChange: (value: SealFilter) => void;
  sealedCount: number;
  unsealedCount: number;
}) {
  const s = t.workflows.sealView;
  const options: { key: SealFilter; label: string; count?: number; icon?: ReactNode }[] = [
    { key: 'all', label: s.filterAll },
    { key: 'sealed', label: t.workflows.sealed, count: sealedCount, icon: <Lock className="w-3 h-3" /> },
    { key: 'unsealed', label: s.notSealed, count: unsealedCount, icon: <LockOpen className="w-3 h-3" /> },
  ];
  return (
    <div className="flex items-center gap-1" role="group" aria-label={s.filterLabel} data-testid="filter-seal">
      {options.map((o) => (
        <button
          key={o.key}
          type="button"
          aria-pressed={value === o.key}
          onClick={() => onChange(o.key)}
          data-testid={`filter-seal-${o.key}`}
          className={`inline-flex items-center gap-1 px-2 py-1 rounded-md whitespace-nowrap transition-colors ${
            value === o.key
              ? 'bg-surface-raised text-text border border-border'
              : 'text-text-tertiary hover:text-text border border-transparent'
          }`}
        >
          {o.icon}
          {o.label}
          {o.count != null && <span className="text-text-tertiary tabular-nums">{o.count}</span>}
        </button>
      ))}
    </div>
  );
}
