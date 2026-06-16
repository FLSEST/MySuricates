import type { LucideIcon } from 'lucide-react';
import type { CSSProperties } from 'react';

interface FeatureTileProps {
  icon: LucideIcon;
  title: string;
  description?: string;
  /** Accent hue (works on light + dark). */
  color: string;
  onClick: () => void;
}

/** Color-accented launcher card for a Simple-mode area. Theme-token base + per-tile hue. */
export function FeatureTile({ icon: Icon, title, description, color, onClick }: FeatureTileProps) {
  return (
    <button
      onClick={onClick}
      style={{ '--tc': color } as CSSProperties}
      className="group relative overflow-hidden text-left rounded-2xl p-[18px] bg-surface border border-border transition-all duration-200 hover:-translate-y-0.5 hover:border-[var(--tc)] hover:shadow-[0_14px_34px_-18px_var(--tc)] focus:outline-none focus-visible:border-[var(--tc)]"
    >
      <div
        className="pointer-events-none absolute inset-0 opacity-[0.12]"
        style={{ background: `radial-gradient(220px 120px at 85% -20%, ${color}, transparent 70%)` }}
      />
      <div
        className="w-10 h-10 rounded-xl flex items-center justify-center mb-3"
        style={{ background: `color-mix(in srgb, ${color} 16%, transparent)`, color }}
      >
        <Icon className="w-5 h-5" />
      </div>
      <h3 className="text-[15px] font-semibold text-text mb-0.5 tracking-tight">{title}</h3>
      {description && <p className="text-xs text-text-secondary leading-snug">{description}</p>}
      <span
        className="absolute top-[18px] right-[18px] opacity-0 group-hover:opacity-90 group-hover:translate-x-0.5 transition"
        style={{ color }}
        aria-hidden
      >
        →
      </span>
    </button>
  );
}
