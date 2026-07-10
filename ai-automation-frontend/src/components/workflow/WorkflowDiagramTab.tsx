import { useT } from '../../i18n';
import { useWorkflowBpmn } from '../../hooks/useWorkflows';
import BpmnDiagramView from './BpmnDiagramView';

export default function WorkflowDiagramTab({ workflowId }: { workflowId: string }) {
  const t = useT();
  const { data: xml, isLoading, isError } = useWorkflowBpmn(workflowId);

  if (isLoading) {
    return <div className="p-6 text-sm text-text-secondary">{t.common.loading}</div>;
  }
  if (isError || !xml) {
    return <div role="alert" className="p-6 text-sm text-text-secondary">{t.workflows.diagramLoadFailed}</div>;
  }
  return (
    <div className="border border-border rounded-lg bg-surface p-4">
      <BpmnDiagramView xml={xml} />
    </div>
  );
}
