import type { ReactNode, CSSProperties } from 'react';
import { WorkflowIcon } from '../../utils/workflowIcon';
import { Play } from 'lucide-react';
import type { Workflow } from '../../types';

/** Status → dot color (shared by Simple home, workflows, runs, schedules). */
const STATUS_DOT: Record<string, string> = {
  COMPLETED: 'var(--color-success)', COMPLETED_WITH_ERRORS: 'var(--color-success)',
  FAILED: 'var(--color-danger)', RUNNING: 'var(--color-info)', PAUSED: 'var(--color-warning)',
  PENDING: 'var(--color-text-tertiary)', CANCELLED: 'var(--color-text-tertiary)', INTERRUPTED: 'var(--color-warning)',
  STOPPED: 'var(--color-text-tertiary)', STARTING: 'var(--color-warning)', ERROR: 'var(--color-danger)',
};

/** Canonical Simple-page container: width + vertical rhythm + fade-in. */
export function SimplePage({ children }: { children: ReactNode }) {
  return <div className="animate-fade-in max-w-4xl mx-auto space-y-6">{children}</div>;
}

/** Greeting (optional) + bold title + optional subtitle, with an optional right-hand slot. */
export function SimplePageHeader({
  greeting, title, subtitle, right,
}: { greeting?: string; title: string; subtitle?: string; right?: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4">
      <div className="min-w-0">
        {greeting && <p className="text-sm text-text-secondary mb-1">{greeting}</p>}
        <h1 className="text-[28px] font-bold text-text tracking-tight leading-none">{title}</h1>
        {subtitle && <p className="text-sm text-text-secondary mt-2">{subtitle}</p>}
      </div>
      {right && <div className="shrink-0">{right}</div>}
    </div>
  );
}

/** Small uppercase section label. */
export function SectionLabel({ children, id, className = 'mb-3' }: { children: ReactNode; id?: string; className?: string }) {
  return <p id={id} className={`text-[11px] tracking-widest uppercase text-text-tertiary font-semibold ${className}`}>{children}</p>;
}

/** Color-accented card. Clickable → button with hover lift/glow; static → plain div. */
export function SimpleCard({
  color, onClick, children, className = '',
}: { color?: string; onClick?: () => void; children: ReactNode; className?: string }) {
  const style = color ? ({ '--tc': color } as CSSProperties) : undefined;
  const glow = color ? (
    <div
      className="pointer-events-none absolute inset-0 opacity-[0.12]"
      style={{ background: `radial-gradient(220px 120px at 85% -20%, ${color}, transparent 70%)` }}
    />
  ) : null;
  const base = 'group relative overflow-hidden rounded-2xl p-[18px] bg-surface border border-border transition-all duration-200';
  if (onClick) {
    return (
      <button
        type="button"
        onClick={onClick}
        style={style}
        className={`${base} text-left hover:-translate-y-0.5 hover:border-[var(--tc)] hover:shadow-[0_14px_34px_-18px_var(--tc)] ${className}`}
      >
        {glow}
        {children}
      </button>
    );
  }
  return (
    <div style={style} className={`${base} ${className}`}>
      {glow}
      {children}
    </div>
  );
}

/** Compact list row with a color hover-accent. Content (clickable area, trailing action) is the caller's. */
export function SimpleRow({
  color, children, className = '',
}: { color?: string; children: ReactNode; className?: string }) {
  return (
    <div
      style={color ? ({ '--tc': color } as CSSProperties) : undefined}
      className={`flex items-center justify-between gap-3 px-4 py-3 rounded-xl bg-surface border border-border transition-colors ${color ? 'hover:border-[var(--tc)]' : 'hover:border-accent/40'} ${className}`}
    >
      {children}
    </div>
  );
}

/** 7×7 status dot. Renders nothing without a status. */
export function StatusDot({ status }: { status?: string }) {
  if (!status) return null;
  return <span className="w-[7px] h-[7px] rounded-full shrink-0" style={{ background: STATUS_DOT[status] ?? 'var(--color-text-tertiary)' }} />;
}

/** Compact start chip for one workflow: icon, name, last run status. Starting is the caller's (`useSimpleRun`). */
export function RunChip({
  workflow, status, onRun,
}: { workflow: Pick<Workflow, 'name' | 'icon'>; status?: string; onRun: () => void }) {
  return (
    <button
      type="button"
      onClick={onRun}
      className="group flex items-center gap-2.5 rounded-xl border border-border bg-surface px-3 py-2 hover:border-accent/40 hover:bg-surface-raised transition-colors"
    >
      <span className="text-lg leading-none text-text-secondary">{workflow.icon ? <WorkflowIcon icon={workflow.icon} className="w-4 h-4" /> : '⚡'}</span>
      <span className="text-[13px] font-medium text-text whitespace-nowrap">{workflow.name}</span>
      <StatusDot status={status} />
      <Play className="w-3 h-3 text-text-tertiary group-hover:text-accent transition-colors" />
    </button>
  );
}
