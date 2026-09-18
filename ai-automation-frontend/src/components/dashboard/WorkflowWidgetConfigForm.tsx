import { useState } from 'react';
import { Button, Select } from '../ui';
import { useT } from '../../i18n';
import { useWorkflows } from '../../hooks/useWorkflows';
import type { WidgetConfigFormProps } from './widgetDefinition';
import { isWorkflowId } from './workflowWidgetContract';

/**
 * Asks which workflow a workflow widget shows (spec E3). The only configuration both widgets have —
 * one id, stored as a primitive config value, so the layout format stays v2.
 *
 * Workflow names are user text and appear as option text; ids that the endpoint would refuse are
 * left out instead of producing a widget that can never load.
 */
export function WorkflowWidgetConfigForm({ onSubmit, onCancel }: WidgetConfigFormProps) {
  const t = useT();
  const texts = t.dashboard.workflowWidgets;
  const { data: workflows, isLoading } = useWorkflows();
  const [workflowId, setWorkflowId] = useState('');

  const options = [
    { value: '', label: texts.selectPlaceholder },
    ...(workflows ?? []).filter((workflow) => isWorkflowId(workflow.id))
      .map((workflow) => ({ value: workflow.id, label: workflow.name })),
  ];

  return (
    <div className="space-y-2">
      <Select
        aria-label={texts.selectWorkflow}
        options={options}
        value={workflowId}
        disabled={isLoading}
        onChange={(event) => setWorkflowId(event.target.value)}
      />
      <div className="flex flex-wrap gap-2">
        <Button size="sm" disabled={!isWorkflowId(workflowId)} onClick={() => onSubmit({ workflowId })}>
          {texts.add}
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>{texts.cancel}</Button>
      </div>
    </div>
  );
}
