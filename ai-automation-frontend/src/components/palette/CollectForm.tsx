import { useEffect, useRef } from 'react';
import { Play, ArrowLeft } from 'lucide-react';
import { WorkflowIcon } from '../../utils/workflowIcon';
import { useT } from '../../i18n';
import type { Workflow } from '../../types';

export interface CollectingState {
  workflow: Workflow;
  variables: string[];
  values: Record<string, string>;
}

interface CollectFormProps {
  state: CollectingState;
  onChange: (values: Record<string, string>) => void;
  onSubmit: () => void;
  onCancel: () => void;
  loading: boolean;
  t: ReturnType<typeof useT>;
}

export function CollectForm({ state, onChange, onSubmit, onCancel, loading, t }: CollectFormProps) {
  const firstRef = useRef<HTMLInputElement>(null);
  useEffect(() => { firstRef.current?.focus(); }, []);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>, idx: number) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      if (idx === state.variables.length - 1 || (e.metaKey || e.ctrlKey)) {
        onSubmit();
      } else {
        const form = (e.target as HTMLInputElement).form;
        const nextInput = form?.elements[idx + 1] as HTMLInputElement | undefined;
        nextInput?.focus();
      }
    }
  };

  return (
    <form
      onSubmit={(e) => { e.preventDefault(); onSubmit(); }}
      className="divide-y divide-border"
    >
      <div className="flex items-center gap-2 px-4 py-3">
        <WorkflowIcon icon={state.workflow.icon} className="w-4 h-4 shrink-0" />
        <div className="flex-1 min-w-0">
          <div className="text-xs text-text-tertiary">{t.commandPalette.collectVariablesHeader}</div>
          <div className="text-sm text-text truncate">{state.workflow.name}</div>
        </div>
      </div>
      <div className="px-4 py-3 space-y-2 max-h-[50vh] overflow-y-auto">
        {state.variables.map((v, idx) => (
          <label key={v} className="block">
            <div className="text-[11px] text-text-tertiary mb-1 font-mono">{v}</div>
            <input
              ref={idx === 0 ? firstRef : undefined}
              type="text"
              value={state.values[v] ?? ''}
              onChange={(e) => onChange({ ...state.values, [v]: e.target.value })}
              onKeyDown={(e) => handleKeyDown(e, idx)}
              placeholder={state.workflow.defaultContext?.[v] ?? ''}
              className="w-full px-3 py-1.5 text-sm bg-surface-raised border border-border rounded-md text-text focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </label>
        ))}
      </div>
      <div className="flex items-center justify-between gap-2 px-4 py-3">
        <button
          type="button"
          onClick={onCancel}
          className="flex items-center gap-1 px-3 py-1.5 text-xs text-text-tertiary hover:text-text rounded-md hover:bg-surface-raised"
        >
          <ArrowLeft className="w-3.5 h-3.5" />
          {t.commandPalette.collectCancel}
        </button>
        <button
          type="submit"
          disabled={loading}
          className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-white bg-accent rounded-md hover:bg-accent/90 disabled:opacity-50"
        >
          <Play className="w-3.5 h-3.5" />
          {t.commandPalette.collectStart}
          <span className="ml-1 text-[10px] border border-white/30 rounded-sm px-1">⏎</span>
        </button>
      </div>
    </form>
  );
}
