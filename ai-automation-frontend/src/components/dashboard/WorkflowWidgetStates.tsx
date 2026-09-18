import { useNavigate } from 'react-router-dom';
import { Card } from '../ui';
import { useT } from '../../i18n';

interface WorkflowWidgetMessageProps {
  /** Heading of the card — the workflow name or the widget's label; plain text. */
  label: string;
  /** Why there is nothing to show. One of the fixed i18n texts, never anything from a run. */
  message: string;
  workflowId?: string;
  runId?: string;
}

/**
 * The neutral state of both workflow widgets (spec E3): a heading, a fixed message and — when there
 * is a run — a button to it. Never raw output, never a substitute "green", never a link built from
 * content: the route is assembled from ids that come from the endpoint, not from the contract.
 */
export function WorkflowWidgetMessage({ label, message, workflowId, runId }: WorkflowWidgetMessageProps) {
  const t = useT();
  const navigate = useNavigate();
  return (
    <Card className="flex flex-col gap-2">
      <p className="text-sm font-medium text-text break-words">{label}</p>
      <p className="text-xs text-text-secondary break-words">{message}</p>
      {workflowId && runId && (
        <button
          type="button"
          onClick={() => navigate(`/workflows/${workflowId}/run/${runId}`)}
          className="self-start text-xs text-accent hover:text-accent-hover"
        >
          {t.dashboard.workflowWidgets.openRun}
        </button>
      )}
    </Card>
  );
}
