import { useState } from 'react';
import { ChevronRight, ChevronDown } from 'lucide-react';
import { Badge } from '../ui';
import { useT } from '../../i18n';
import type { FieldDiff } from '../../utils/iterationDiff';

export function IterationDiffSection({ diffs, prevIteration }: { diffs: FieldDiff[]; prevIteration: number }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const labels = t.wishes.iterationDiff;

  if (diffs.length === 0) return null;

  const toggleLabel = (open ? labels.toggleHide : labels.toggleShow).replace('{prev}', String(prevIteration));

  return (
    <div className="pt-2 border-t border-border/60">
      <button
        type="button"
        className="flex items-center gap-1.5 text-xs text-accent hover:underline"
        onClick={() => setOpen(v => !v)}
      >
        {open ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
        <span>{toggleLabel} ({diffs.length})</span>
      </button>
      {open && (
        <div className="mt-2 space-y-2">
          {diffs.map((diff, i) => (
            <div key={`${diff.group}-${diff.key}-${i}`} className="rounded border border-border/60 bg-surface-base p-2 space-y-1">
              <div className="flex items-center gap-2 text-xs">
                <Badge variant={diff.kind === 'added' ? 'success' : 'warning'}>
                  {diff.kind === 'added' ? labels.added : labels.changed}
                </Badge>
                <span className="text-text-secondary font-medium">
                  {labels.fields[diff.key]}
                </span>
              </div>
              {diff.kind === 'changed' && diff.before && (
                <div className="text-xs">
                  <div className="text-text-tertiary uppercase tracking-wide">{labels.before}</div>
                  <pre className="whitespace-pre-wrap break-words text-danger/80 line-through">{diff.before}</pre>
                </div>
              )}
              <div className="text-xs">
                <div className="text-text-tertiary uppercase tracking-wide">{labels.after}</div>
                <pre className="whitespace-pre-wrap break-words text-success">{diff.after}</pre>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
