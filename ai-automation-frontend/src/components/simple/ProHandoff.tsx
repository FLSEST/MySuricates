import { Sparkles } from 'lucide-react';
import { useT } from '../../i18n';
import { useOpenInPro } from '../../hooks/useOpenInPro';
import { Button } from '../ui';

/** Small "Pro" pill: marks an entry that leads out of Simple mode into a Pro page. */
export function ProBadge({ className = '' }: { className?: string }) {
  const t = useT();
  return (
    <span
      title={t.simple.handoff.badgeHint}
      className={`inline-flex items-center gap-1 rounded-full border border-border bg-surface-raised px-2 py-0.5 text-[10px] font-semibold text-text-secondary ${className}`}
    >
      <Sparkles className="w-2.5 h-2.5" aria-hidden />
      {t.simple.handoff.badge}
    </span>
  );
}

/** One row: why this needs Pro + a button that switches to Pro (and opens `to`, if given). */
export function ProHandoff({ reason, to }: { reason: string; to?: string }) {
  const t = useT();
  const openInPro = useOpenInPro();
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-surface px-4 py-3">
      <p className="text-sm text-text-secondary min-w-0">{reason}</p>
      <Button
        variant="secondary"
        size="sm"
        icon={<Sparkles className="w-3.5 h-3.5" />}
        onClick={() => openInPro(to)}
      >
        {t.simple.handoff.openInPro}
      </Button>
    </div>
  );
}
