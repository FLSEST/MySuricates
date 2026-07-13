import { useState } from 'react';
import { FileCode2, Info, ListTree, MoveHorizontal, MoveVertical } from 'lucide-react';
import { useT } from '../../i18n';
import { useWorkflowBpmn } from '../../hooks/useWorkflows';
import { Button, Modal } from '../ui';
import { MonacoEditor } from '../shared/MonacoEditor';
import { downloadBlob } from '../../utils/downloadFile';
import type { LineRange } from '../../utils/workflowDefinition';
import BpmnDiagramView, { type DiagramSelection, type DiagramOrientation } from './BpmnDiagramView';

export default function WorkflowDiagramTab({
  workflowId,
  workflowName,
  definitionText,
  lineRanges,
  onSelectBlock,
}: {
  workflowId: string;
  workflowName: string;
  definitionText: string;
  lineRanges: Map<number, LineRange>;
  onSelectBlock?: (selection: DiagramSelection) => void;
}) {
  const t = useT();
  const { data: xml, isLoading, isError } = useWorkflowBpmn(workflowId);
  const [showInfo, setShowInfo] = useState(false);
  const [showDefinition, setShowDefinition] = useState(false);
  const [orientation, setOrientation] = useState<DiagramOrientation>('horizontal');

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
      <div className="flex items-center justify-between mb-3 gap-2 flex-wrap">
        <span className="text-xs text-text-tertiary">{t.workflows.diagramHint}</span>
        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="sm"
            icon={orientation === 'horizontal' ? <MoveVertical className="w-4 h-4" /> : <MoveHorizontal className="w-4 h-4" />}
            onClick={() => setOrientation((o) => (o === 'horizontal' ? 'vertical' : 'horizontal'))}
            title={t.workflows.orientationToggle}
          >
            {orientation === 'horizontal' ? t.workflows.orientationVertical : t.workflows.orientationHorizontal}
          </Button>
          <Button
            variant={showInfo ? 'primary' : 'ghost'}
            size="sm"
            icon={<Info className="w-4 h-4" />}
            onClick={() => setShowInfo((v) => !v)}
            title={t.workflows.diagramInfoToggle}
          >
            {t.workflows.diagramInfoToggle}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            icon={<ListTree className="w-4 h-4" />}
            onClick={() => setShowDefinition(true)}
            title={t.workflows.viewDefinition}
          >
            {t.workflows.viewDefinition}
          </Button>
          <Button
            variant="secondary"
            size="sm"
            icon={<FileCode2 className="w-4 h-4" />}
            onClick={handleExport}
          >
            {t.workflows.exportBpmn}
          </Button>
        </div>
      </div>
      <BpmnDiagramView
        xml={xml}
        onSelectBlock={onSelectBlock}
        infoByBlock={showInfo ? lineRanges : undefined}
        orientation={orientation}
      />

      <Modal open={showDefinition} onClose={() => setShowDefinition(false)} title={t.workflows.viewDefinition} size="xl">
        <p className="text-xs text-text-tertiary mb-2">{t.workflows.definitionHint}</p>
        <MonacoEditor value={definitionText} language="json" height="60vh" readOnly />
      </Modal>
    </div>
  );
}
