import type { CalendarSourceConfig } from '../../types';

interface Props {
  sources: CalendarSourceConfig[];
  onToggle: (sourceId: string) => void;
}

export function CalendarSourceFilter({ sources, onToggle }: Props) {
  return (
    <div className="flex flex-wrap gap-2">
      {sources.map((source) => (
        <button
          key={source.sourceId}
          onClick={() => onToggle(source.sourceId)}
          className={`
            inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium
            transition-all border
            ${source.enabled
              ? 'border-border bg-surface text-text'
              : 'border-transparent bg-surface-secondary text-text-tertiary opacity-50'
            }
          `}
        >
          <span className={`w-2.5 h-2.5 rounded-full ${source.enabled ? source.color : 'bg-gray-400'}`} />
          {source.label}
        </button>
      ))}
    </div>
  );
}
