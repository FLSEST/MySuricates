import { Select } from '../ui';
import { useT } from '../../i18n';
import type { WorkflowOption } from './BlockEditorModal';
import { forEachWorkflowId, withForEachWorkflow } from './forEachWorkflowConfig';

interface ForEachWorkflowFieldProps {
  configJson: string;
  onConfigChange: (config: string) => void;
  allWorkflows: WorkflowOption[] | undefined;
  /** The workflow being edited — it cannot run itself per item. */
  workflowId: string;
}

/** foreach: pick a workflow that runs once per item instead of the command. */
export function ForEachWorkflowField({ configJson, onConfigChange, allWorkflows, workflowId }: ForEachWorkflowFieldProps) {
  const t = useT();
  return (
    <Select
      label={t.workflows.foreachWorkflow}
      value={forEachWorkflowId(configJson)}
      onChange={(e) => onConfigChange(withForEachWorkflow(configJson, e.target.value))}
      options={[
        { value: '', label: t.workflows.foreachWorkflowNone },
        ...(allWorkflows?.filter((w) => w.id !== workflowId).map((w) => ({ value: w.id, label: `${w.icon || ''} ${w.name}`.trim() })) ?? []),
      ]}
    />
  );
}
