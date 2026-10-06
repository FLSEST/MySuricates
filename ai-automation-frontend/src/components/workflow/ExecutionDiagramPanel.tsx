import { useState } from 'react';
import { Eye, EyeOff, MoveHorizontal, MoveVertical } from 'lucide-react';
import { Card, CardHeader, Button } from '../ui';
import BpmnDiagramView, { type RunStatus, type DiagramOrientation, type DiagramSelection } from './BpmnDiagramView';
import { useT } from '../../i18n';

interface ExecutionDiagramPanelProps {
  xml: string;
  statusByBlock: Map<number, RunStatus>;
  onSelectBlock: (sel: DiagramSelection) => void;
}

/** Flow diagram with live status per block; visibility and orientation are local state. */
export function ExecutionDiagramPanel({ xml, statusByBlock, onSelectBlock }: ExecutionDiagramPanelProps) {
  const t = useT();
  const [showFlow, setShowFlow] = useState(true);
  const [flowOrientation, setFlowOrientation] = useState<DiagramOrientation>('horizontal');

  return (
    <Card padding={false}>
      <div className="p-5 pb-3">
        <CardHeader
          title={t.workflows.execution.diagramTitle}
          action={
            <div className="flex items-center gap-2">
              {showFlow && (
                <Button
                  variant="ghost"
                  size="sm"
                  icon={flowOrientation === 'horizontal' ? <MoveVertical className="w-4 h-4" /> : <MoveHorizontal className="w-4 h-4" />}
                  onClick={() => setFlowOrientation((o) => (o === 'horizontal' ? 'vertical' : 'horizontal'))}
                  title={t.workflows.orientationToggle}
                >
                  {flowOrientation === 'horizontal' ? t.workflows.orientationVertical : t.workflows.orientationHorizontal}
                </Button>
              )}
              <Button
                variant="ghost"
                size="sm"
                icon={showFlow ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                onClick={() => setShowFlow((v) => !v)}
                title={showFlow ? t.workflows.execution.hideDiagram : t.workflows.execution.showDiagram}
              >
                {showFlow ? t.workflows.execution.hideDiagram : t.workflows.execution.showDiagram}
              </Button>
            </div>
          }
        />
      </div>
      {showFlow && (
        <div className="px-5 pb-5">
          <BpmnDiagramView
            xml={xml}
            statusByBlock={statusByBlock}
            orientation={flowOrientation}
            onSelectBlock={onSelectBlock}
          />
        </div>
      )}
    </Card>
  );
}
