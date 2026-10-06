import { useNavigate } from 'react-router-dom';
import { useT } from '../../i18n';
import { WorkflowIcon } from '../../utils/workflowIcon';
import type { Workflow } from '../../types';

const MAX_VISIBLE = 5;

/** Panel 3 of the session takeover: the first workflows of the chosen project, the rest as a count. */
export function ExistingWorkflowsList({ workflows }: { workflows: Workflow[] }) {
  const t = useT();
  const navigate = useNavigate();
  const visible = workflows.slice(0, MAX_VISIBLE);
  const rest = workflows.length - MAX_VISIBLE;

  if (workflows.length === 0) {
    return <p className="text-xs text-text-secondary italic">{t.dialog.noExistingWorkflows}</p>;
  }

  return (
    <div className="flex flex-col gap-1">
      {visible.map(w => (
        <button
          key={w.id}
          onClick={() => navigate(`/workflows/${w.id}`)}
          className="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-surface-hover text-left group"
        >
          <WorkflowIcon icon={w.icon} className="w-3.5 h-3.5 text-text-secondary shrink-0" />
          <span className="text-xs text-text truncate group-hover:text-accent transition-colors">
            {w.name}
          </span>
        </button>
      ))}
      {rest > 0 && (
        <span className="text-xs text-text-secondary px-2">+{rest} {t.dialog.moreWorkflows}</span>
      )}
    </div>
  );
}
