import { AlertTriangle, CheckCircle2, XOctagon } from 'lucide-react';
import type { WorkflowRun } from '../../types';
import { useT } from '../../i18n';

interface GateStatusBannerProps {
  run: WorkflowRun;
  className?: string;
}

type GateKey = 'GREEN' | 'YELLOW' | 'RED';

const gateConfig: Record<GateKey, {
  container: string;
  iconWrap: string;
  iconColor: string;
  title: string;
  Icon: typeof AlertTriangle;
}> = {
  GREEN: {
    container: 'border-green-300/60 bg-green-50 dark:border-green-700/60 dark:bg-green-900/20',
    iconWrap: 'bg-green-100 dark:bg-green-900/40',
    iconColor: 'text-green-700 dark:text-green-300',
    title: 'text-green-900 dark:text-green-100',
    Icon: CheckCircle2,
  },
  YELLOW: {
    container: 'border-yellow-300/60 bg-yellow-50 dark:border-yellow-700/60 dark:bg-yellow-900/20',
    iconWrap: 'bg-yellow-100 dark:bg-yellow-900/40',
    iconColor: 'text-yellow-700 dark:text-yellow-300',
    title: 'text-yellow-900 dark:text-yellow-100',
    Icon: AlertTriangle,
  },
  RED: {
    container: 'border-red-300/60 bg-red-50 dark:border-red-700/60 dark:bg-red-900/20',
    iconWrap: 'bg-red-100 dark:bg-red-900/40',
    iconColor: 'text-red-700 dark:text-red-300',
    title: 'text-red-900 dark:text-red-100',
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
