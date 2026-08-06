import { Sparkles } from 'lucide-react';

interface AssistantRowProps {
  query: string;
  selected: boolean;
  onHover: () => void;
  onClick: () => void;
  label: string;
  borderTop: boolean;
  numberHint?: number;
}

export function AssistantRow({ query, selected, onHover, onClick, label, borderTop, numberHint }: AssistantRowProps) {
  return (
    <div
      onMouseEnter={onHover}
      onClick={onClick}
      className={`flex items-center gap-3 px-4 py-2 cursor-pointer ${
        borderTop ? 'border-t border-border' : ''
      } ${selected ? 'bg-accent/10' : 'hover:bg-surface-raised'}`}
    >
      {numberHint !== undefined && (
        <span className="shrink-0 text-[9px] text-text-tertiary font-mono w-4 text-right">
          ⌥{numberHint}
        </span>
      )}
      <span className="shrink-0 text-accent">
        <Sparkles className="w-4 h-4" />
      </span>
      <div className="flex-1 min-w-0">
        <div className="text-sm text-text truncate">
          {label}: <span className="text-text-tertiary">»{query}«</span>
        </div>
      </div>
      <span className="text-[10px] text-text-tertiary border border-border px-1.5 py-0.5 rounded-sm shrink-0">
        ⏎
      </span>
    </div>
  );
}
