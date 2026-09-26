import type { WorkflowRun } from '../../types';

interface GateStatusBadgeProps {
  /** Only `metadata.gate_status` is read — a list row without steps is enough. */
  run: Pick<WorkflowRun, 'metadata'>;
  className?: string;
  compact?: boolean;
}

// Status tokens instead of fixed palette classes, so every theme colours the gate (6c94dc62)
const gateConfig = {
  GREEN:  { bg: 'bg-success-subtle', text: 'text-success', dot: 'bg-success', label: 'OK' },
  YELLOW: { bg: 'bg-warning-subtle', text: 'text-warning', dot: 'bg-warning', label: 'Findings' },
  RED:    { bg: 'bg-danger-subtle',  text: 'text-danger',  dot: 'bg-danger',  label: 'Critical' },
};

export function GateStatusBadge({ run, className = '', compact = false }: GateStatusBadgeProps) {
  const gateStatus = run.metadata?.gate_status as string | undefined;
  if (!gateStatus || !(gateStatus in gateConfig)) return null;

  const cfg = gateConfig[gateStatus as keyof typeof gateConfig];
  const description = run.metadata?.gate_description as string | undefined;
  const category = run.metadata?.gate_category as string | undefined;

  // Show description as label if available and not compact, otherwise use default label
  const displayLabel = !compact && description
    ? description.length > 40 ? description.substring(0, 40) + '…' : description
    : cfg.label;

  const tooltip = [description, category ? `(${category})` : undefined].filter(Boolean).join(' ') || undefined;

  return (
    <span
      title={tooltip}
      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${cfg.bg} ${cfg.text} ${className}`}
    >
      <span className={`w-1.5 h-1.5 rounded-full ${cfg.dot}`} />
      {displayLabel}
    </span>
  );
}
