import { Play, ExternalLink, Star, RotateCw } from 'lucide-react';
import { WorkflowIcon } from '../../utils/workflowIcon';
import { useT } from '../../i18n';
import type { PaletteItem, BadgeTone, PaletteMatch, HighlightKey } from './contexts/types';

const TONE_CLASSES: Record<BadgeTone, string> = {
  success: 'bg-success/10 text-success',
  danger: 'bg-danger/10 text-danger',
  warn: 'bg-warning/10 text-warning',
  info: 'bg-accent/10 text-accent',
  neutral: 'bg-surface-raised text-text-tertiary',
};

interface PaletteRowProps {
  item: PaletteItem;
  selected: boolean;
  pinned: boolean;
  onHover: () => void;
  onRun: () => void;
  onOpen: () => void;
  onTogglePin: () => void;
  altAction?: {
    label: string;
    shortcut: string;
    run: () => void;
  };
  numberHint?: number;
  t: ReturnType<typeof useT>;
}

function renderHighlighted(text: string, matches: PaletteMatch[] | undefined, key: HighlightKey): React.ReactNode {
  if (!text) return text;
  const ranges = matches?.filter((m) => m.key === key).flatMap((m) => m.indices) ?? [];
  if (ranges.length === 0) return text;
  const sorted = [...ranges].sort((a, b) => a[0] - b[0]);
  const out: React.ReactNode[] = [];
  let cursor = 0;
  sorted.forEach(([start, end], i) => {
    if (start > cursor) out.push(text.slice(cursor, start));
    out.push(
      <span key={`h${i}`} className="font-semibold text-accent">
        {text.slice(start, end + 1)}
      </span>,
    );
    cursor = end + 1;
  });
  if (cursor < text.length) out.push(text.slice(cursor));
  return out;
}

export function PaletteRow({ item, selected, pinned, onHover, onRun, onOpen, onTogglePin, altAction, numberHint, t }: PaletteRowProps) {
  return (
    <div
      onMouseEnter={onHover}
      onClick={onRun}
      className={`flex items-center gap-3 px-4 py-2 cursor-pointer ${
        selected ? 'bg-accent/10' : 'hover:bg-surface-raised'
      }`}
    >
      {numberHint !== undefined && (
        <span className="shrink-0 text-[9px] text-text-tertiary font-mono w-4 text-right">
          ⌥{numberHint}
        </span>
      )}
      <span className="shrink-0">
        <WorkflowIcon icon={item.icon ?? null} className="w-4 h-4" />
      </span>
      <div className="flex-1 min-w-0">
        <div className="text-sm text-text truncate">{renderHighlighted(item.title, item.matches, 'title')}</div>
        {item.subtitle && (
          <div className="text-xs text-text-tertiary truncate">
            {renderHighlighted(item.subtitle, item.matches, 'subtitle')}
          </div>
        )}
      </div>
      {item.badge && (
        <span className={`text-[10px] px-1.5 py-0.5 rounded-sm shrink-0 ${TONE_CLASSES[item.badge.tone]}`}>
          {item.badge.label}
        </span>
      )}
      <button
        onClick={(e) => { e.stopPropagation(); onTogglePin(); }}
        className={`p-1 rounded-sm transition-colors ${
          pinned ? 'text-accent' : 'text-text-tertiary hover:text-accent'
        }`}
        title={pinned ? t.commandPalette.unpinHint : t.commandPalette.pinHint}
        aria-label={pinned ? t.commandPalette.unpinHint : t.commandPalette.pinHint}
      >
        <Star className={`w-3.5 h-3.5 ${pinned ? 'fill-current' : ''}`} />
      </button>
      {altAction && (
        <button
          onClick={(e) => { e.stopPropagation(); altAction.run(); }}
          className="p-1 rounded-sm hover:bg-warning/20 text-warning"
          title={`${altAction.label} (Alt+${altAction.shortcut.toUpperCase()})`}
          aria-label={altAction.label}
        >
          <RotateCw className="w-3.5 h-3.5" />
        </button>
      )}
      <button
        onClick={(e) => { e.stopPropagation(); onOpen(); }}
        className="p-1 rounded-sm hover:bg-surface text-text-tertiary hover:text-text"
        title={t.commandPalette.openHint}
        aria-label={t.commandPalette.openHint}
      >
        <ExternalLink className="w-3.5 h-3.5" />
      </button>
      <button
        onClick={(e) => { e.stopPropagation(); onRun(); }}
        className="p-1 rounded-sm hover:bg-accent/20 text-accent"
        title={t.commandPalette.runHint}
        aria-label={t.commandPalette.runHint}
      >
        <Play className="w-3.5 h-3.5" />
      </button>
    </div>
  );
}
