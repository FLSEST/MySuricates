import type { TodoStatus } from '../../types';

interface StatusDotsProps {
  current: TodoStatus;
  onChange: (s: TodoStatus) => void;
}

export function StatusDots({ current, onChange }: StatusDotsProps) {
  return (
    <div className="flex items-center gap-1 shrink-0">
      {/* OPEN */}
      <button
        onClick={(e) => { e.stopPropagation(); onChange('OPEN'); }}
        className={`w-5 h-5 rounded-full border-2 flex items-center justify-center transition-colors ${
          current === 'OPEN'
            ? 'border-text-tertiary bg-surface-raised'
            : 'border-border hover:border-text-tertiary'
        }`}
        title="Open"
      />
      {/* IN_PROGRESS */}
      <button
        onClick={(e) => { e.stopPropagation(); onChange('IN_PROGRESS'); }}
        className={`w-5 h-5 rounded-full border-2 flex items-center justify-center transition-colors ${
          current === 'IN_PROGRESS'
            ? 'border-warning bg-warning/20'
            : 'border-border hover:border-warning'
        }`}
        title="In Progress"
      >
        {current === 'IN_PROGRESS' && (
          <div className="w-2.5 h-2.5 rounded-full bg-warning" style={{ clipPath: 'inset(0 50% 0 0)' }}>
            <div className="w-full h-full rounded-full bg-warning" />
          </div>
        )}
      </button>
      {/* DONE */}
      <button
        onClick={(e) => { e.stopPropagation(); onChange('DONE'); }}
        className={`w-5 h-5 rounded-full border-2 flex items-center justify-center transition-colors ${
          current === 'DONE'
            ? 'border-success bg-success text-white'
            : 'border-border hover:border-success'
        }`}
        title="Done"
      >
        {current === 'DONE' && (
          <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
          </svg>
        )}
      </button>
      {/* BACKLOG */}
      <button
        onClick={(e) => { e.stopPropagation(); onChange('BACKLOG'); }}
        className={`w-5 h-5 rounded-full border-2 flex items-center justify-center transition-colors ${
          current === 'BACKLOG'
            ? 'border-text-tertiary/60 bg-text-tertiary/20'
            : 'border-border hover:border-text-tertiary/60'
        }`}
        title="Backlog"
      >
        {current === 'BACKLOG' && (
          <div className="w-2 h-2 rounded-full bg-text-tertiary/60" />
        )}
      </button>
    </div>
  );
}
