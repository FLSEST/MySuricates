import { FileCode2 } from 'lucide-react';
import { useT } from '../../i18n';
import { useWorkflowBpmn } from '../../hooks/useWorkflows';
import { Button } from '../ui';
import { downloadBlob } from '../../utils/downloadFile';
import BpmnDiagramView from './BpmnDiagramView';

export default function WorkflowDiagramTab({
  workflowId,
  workflowName,
}: {
  workflowId: string;
  workflowName: string;
}) {
  const t = useT();
  const { data: xml, isLoading, isError } = useWorkflowBpmn(workflowId);

  if (isLoading) {
    return <div className="p-6 text-sm text-text-secondary">{t.common.loading}</div>;
  }
  if (isError || !xml) {
    return <div role="alert" className="p-6 text-sm text-text-secondary">{t.workflows.diagramLoadFailed}</div>;
  }

  const handleExport = () => {
    const safeName = workflowName.replace(/[^a-zA-Z0-9_-]/g, '_');
    downloadBlob(new Blob([xml], { type: 'application/xml' }), `${safeName}.bpmn`);
  };

  return (
    <div className="border border-border rounded-lg bg-surface p-4">
      <div className="flex items-center justify-between mb-3">
        <span className="text-xs text-text-tertiary">{t.workflows.diagramHint}</span>
        <Button
          variant="secondary"
          size="sm"
          icon={<FileCode2 className="w-4 h-4" />}
          onClick={handleExport}
        >
          {t.workflows.exportBpmn}
        </Button>
      </div>
      <BpmnDiagramView xml={xml} />
    </div>
  );
}
