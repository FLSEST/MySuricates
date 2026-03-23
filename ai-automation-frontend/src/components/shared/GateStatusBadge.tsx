import type { WorkflowRun } from '../../types';

interface GateStatusBadgeProps {
  run: WorkflowRun;
  className?: string;
}

const gateConfig = {
  GREEN:  { bg: 'bg-green-100 dark:bg-green-900/30',  text: 'text-green-700 dark:text-green-400',  dot: 'bg-green-500',  label: 'Gate: OK' },
  YELLOW: { bg: 'bg-yellow-100 dark:bg-yellow-900/30', text: 'text-yellow-700 dark:text-yellow-400', dot: 'bg-yellow-500', label: 'Gate: Warn' },
  RED:    { bg: 'bg-red-100 dark:bg-red-900/30',       text: 'text-red-700 dark:text-red-400',       dot: 'bg-red-500',    label: 'Gate: Fail' },
};

export function GateStatusBadge({ run, className = '' }: GateStatusBadgeProps) {
  const gateStatus = run.metadata?.gate_status as string | undefined;
  if (!gateStatus || !(gateStatus in gateConfig)) return null;

  const cfg = gateConfig[gateStatus as keyof typeof gateConfig];
  const description = run.metadata?.gate_description as string | undefined;
  const category = run.metadata?.gate_category as string | undefined;
  const tooltip = [description, category ? `(${category})` : undefined].filter(Boolean).join(' ') || undefined;

  return (
    <span
      title={tooltip}
      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${cfg.bg} ${cfg.text} ${className}`}
    >
      <span className={`w-1.5 h-1.5 rounded-full ${cfg.dot}`} />
      {cfg.label}
    </span>
  );
}
