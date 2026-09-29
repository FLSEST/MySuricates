import { AlertTriangle, CheckCircle2, XOctagon } from 'lucide-react';
import type { WorkflowRun } from '../../types';
import { useT } from '../../i18n';

interface GateStatusBannerProps {
  run: WorkflowRun;
  className?: string;
}

type GateKey = 'GREEN' | 'YELLOW' | 'RED';

// Status tokens instead of fixed palette classes, so every theme colours the banner (19ebb8a5)
const gateConfig: Record<GateKey, {
  container: string;
  iconWrap: string;
  iconColor: string;
  title: string;
  Icon: typeof AlertTriangle;
}> = {
  GREEN: {
    container: 'border-success/30 bg-success-subtle',
    iconWrap: 'bg-success/15',
    iconColor: 'text-success',
    title: 'text-success',
    Icon: CheckCircle2,
  },
  YELLOW: {
    container: 'border-warning/30 bg-warning-subtle',
    iconWrap: 'bg-warning/15',
    iconColor: 'text-warning',
    title: 'text-warning',
    Icon: AlertTriangle,
  },
  RED: {
    container: 'border-danger/30 bg-danger-subtle',
    iconWrap: 'bg-danger/10',
    iconColor: 'text-danger',
    title: 'text-danger',
    Icon: XOctagon,
  },
};

export function GateStatusBanner({ run, className = '' }: GateStatusBannerProps) {
  const t = useT();
  const gateStatus = run.metadata?.gate_status as string | undefined;
  if (!gateStatus || !(gateStatus in gateConfig)) return null;

  const key = gateStatus as GateKey;
  const cfg = gateConfig[key];
  const description = run.metadata?.gate_description as string | undefined;
  const category = run.metadata?.gate_category as string | undefined;

  const titleMap = t.workflows.execution.gateBanner;
  const title = titleMap[key === 'GREEN' ? 'green' : key === 'YELLOW' ? 'yellow' : 'red'];

  return (
    <div
      role={key === 'GREEN' ? 'status' : 'alert'}
      aria-live="polite"
      data-gate-status={key}
      className={`flex items-start gap-4 rounded-xl border px-4 py-3 ${cfg.container} ${className}`}
    >
      <div className={`shrink-0 rounded-lg p-2 ${cfg.iconWrap}`}>
        <cfg.Icon className={`w-5 h-5 ${cfg.iconColor}`} aria-hidden="true" />
      </div>
      <div className="flex-1 min-w-0">
        <div className={`text-sm font-semibold ${cfg.title}`}>{title}</div>
        {description && (
          <p className="text-sm text-text-secondary mt-0.5 whitespace-pre-wrap break-words">
            {description}
          </p>
        )}
        {category && (
          <div className="mt-1 text-xs uppercase tracking-wide text-text-tertiary">
            {titleMap.categoryLabel}: {category}
          </div>
        )}
      </div>
    </div>
  );
}
