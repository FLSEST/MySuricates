import type { TranslationKeys } from '../../i18n';
import type { WorkflowViewMode } from '../../hooks/useWorkflowListView';

const MODES: WorkflowViewMode[] = ['classic', 'discovery'];

/** Switch of the workflow list between the classic project list and the discover view. */
export function WorkflowViewModeTabs({ t, value, onChange }: {
  t: TranslationKeys;
  value: WorkflowViewMode;
  onChange: (mode: WorkflowViewMode) => void;
}) {
  const labels: Record<WorkflowViewMode, string> = {
    classic: t.workflows.viewModeClassic,
    discovery: t.workflows.viewModeDiscovery,
  };
  return (
    <div className="flex items-center gap-1 mb-2 text-xs" role="tablist">
      {MODES.map((mode) => (
        <button
          key={mode}
          role="tab"
          aria-selected={value === mode}
          onClick={() => onChange(mode)}
          className={`px-2.5 py-1 rounded-md transition-colors ${
            value === mode
              ? 'bg-surface-raised text-text border border-border'
              : 'text-text-tertiary hover:text-text'
          }`}
        >
          {labels[mode]}
        </button>
      ))}
    </div>
  );
}
