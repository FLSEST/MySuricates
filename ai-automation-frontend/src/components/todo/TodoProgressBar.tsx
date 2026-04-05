import { Circle } from 'lucide-react';
import { useT } from '../../i18n';

interface TodoProgressBarProps {
  open: number;
  inProgress: number;
  done: number;
  backlog: number;
}

export function TodoProgressBar({ open, inProgress, done, backlog }: TodoProgressBarProps) {
  const t = useT();
  const activeTotal = open + inProgress + done;
  if (activeTotal + backlog === 0) return null;

  return (
    <div className="flex items-center gap-4">
      <div className="flex-1 h-2 rounded-full bg-surface overflow-hidden flex">
        {done > 0 && activeTotal > 0 && (
          <div
            className="h-full bg-success transition-all duration-500"
            style={{ width: `${(done / activeTotal) * 100}%` }}
          />
        )}
        {inProgress > 0 && activeTotal > 0 && (
          <div
            className="h-full bg-warning transition-all duration-500"
            style={{ width: `${(inProgress / activeTotal) * 100}%` }}
          />
        )}
        {open > 0 && activeTotal > 0 && (
          <div
            className="h-full bg-text-tertiary/40 transition-all duration-500"
            style={{ width: `${(open / activeTotal) * 100}%` }}
          />
        )}
      </div>
      <div className="flex items-center gap-3 shrink-0 text-xs">
        <span className="flex items-center gap-1 text-text-tertiary">
          <Circle className="w-2 h-2 fill-text-tertiary/40 text-text-tertiary/40" />
          {open} {t.todos.statuses.OPEN.toLowerCase()}
        </span>
        <span className="flex items-center gap-1 text-warning">
          <Circle className="w-2 h-2 fill-warning text-warning" />
          {inProgress} {t.todos.statuses.IN_PROGRESS.toLowerCase()}
        </span>
        <span className="flex items-center gap-1 text-success">
          <Circle className="w-2 h-2 fill-success text-success" />
          {done} {t.todos.statuses.DONE.toLowerCase()}
        </span>
        {backlog > 0 && (
          <span className="flex items-center gap-1 text-text-tertiary/60">
            <Circle className="w-2 h-2 fill-text-tertiary/30 text-text-tertiary/30" />
            {backlog} {t.todos.statuses.BACKLOG.toLowerCase()}
          </span>
        )}
      </div>
    </div>
  );
}
