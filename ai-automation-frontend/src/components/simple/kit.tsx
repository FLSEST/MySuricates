import type { ReactNode, CSSProperties } from 'react';

/** Status → dot color (shared by Simple home, workflows, runs, schedules). */
const STATUS_DOT: Record<string, string> = {
  COMPLETED: '#34d399', COMPLETED_WITH_ERRORS: '#34d399',
  FAILED: '#fb7185', RUNNING: '#38bdf8', PAUSED: '#fbbf24',
  PENDING: '#5b6678', CANCELLED: '#5b6678', INTERRUPTED: '#fbbf24',
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
export function SectionLabel({ children }: { children: ReactNode }) {
  return <p className="text-[11px] tracking-widest uppercase text-text-tertiary font-semibold mb-3">{children}</p>;
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
  return <span className="w-[7px] h-[7px] rounded-full shrink-0" style={{ background: STATUS_DOT[status] ?? '#5b6678' }} />;
}
