import { FolderOpen } from 'lucide-react';
import { useToast } from '../../hooks/useToast';
import { useT } from '../../i18n';
import { openPath } from '../../api/client';

/** Input variables of a run as chips; values that look like an absolute path can be opened. */
export function RunInputVariables({ inputContext }: { inputContext: Record<string, unknown> }) {
  const t = useT();
  const toast = useToast();
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-xs font-medium text-text-secondary">{t.workflows.execution.inputVariables}:</span>
      {Object.entries(inputContext).map(([key, value]) => {
        const strVal = String(value);
        const isPath = /^\/(?:[\w.-]+\/)+[\w.-]*$/.test(strVal);
        return (
          <span
            key={key}
            className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-surface-raised text-xs font-mono"
          >
            <span className="text-text-secondary">{key}</span>
            <span className="text-text-tertiary">=</span>
            <span className="text-text">{strVal}</span>
            {isPath && (
              <button
                onClick={() => openPath(strVal).catch(() => toast.error(t.workflows.execution.openPathFailed))}
                className="ml-0.5 text-text-tertiary hover:text-accent transition-colors"
                title={t.workflows.execution.openFolder}
              >
                <FolderOpen className="w-3 h-3" />
              </button>
            )}
          </span>
        );
      })}
    </div>
  );
}
