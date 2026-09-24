import { lazy, Suspense, useState, type Ref } from 'react';
import { Plus } from 'lucide-react';
import { Card, CardHeader, Button } from '../ui';
import { SortableBlockList } from './SortableBlockList';
import { useModuleEnabled } from '../../hooks/useModules';
import { useT } from '../../i18n';
import { buildDefinitionText, blockLineRanges } from '../../utils/workflowDefinition';
import type { DiagramSelection } from './bpmnLayout';
import type { Workflow } from '../../types';

const WorkflowDiagramTab = lazy(() => import('./WorkflowDiagramTab'));
type BlockFlash = import('./BpmnDiagramView').BlockFlash;

interface WorkflowBlocksTabProps {
  workflow: Workflow;
  onAddBlock: () => void;
  onEditBlock: (index: number) => void;
  onRemoveBlock: (index: number) => void;
  onSelectFromDiagram: (selection: DiagramSelection) => void;
  /** Receives the diagram section (the legacy `?tab=diagram` link scrolls to it once). */
  diagramRef?: Ref<HTMLElement>;
}

/**
 * Blocks tab of the workflow detail page: the sortable block list and, with the `bpmn` module,
 * the diagram below it. A sealed definition is read-only (no drag, edit or remove).
 */
export function WorkflowBlocksTab({
  workflow, onAddBlock, onEditBlock, onRemoveBlock, onSelectFromDiagram, diagramRef,
}: WorkflowBlocksTabProps) {
  const t = useT();
  const bpmnModule = useModuleEnabled('bpmn');
  // Querverweis per Hover: Maus über einem Listen-Block lässt seinen Kasten im Diagramm blinken und
  // umgekehrt (Klick öffnet hier den Editor, deshalb Hover statt Klick wie in der Workflow-Liste)
  const [hoverFlash, setHoverFlash] = useState<(BlockFlash & { target: 'list' | 'diagram' }) | null>(null);
  // Günstig (JSON.stringify der Blöcke) — daher ohne useMemo
  const definitionText = buildDefinitionText(workflow.blocks);
  const definitionLineRanges = blockLineRanges(workflow.blocks);

  return (
    <>
      <Card padding={false}>
        <div className="p-5 pb-0">
          <CardHeader
            title={`${t.workflows.blocks} (${workflow.blocks.length})`}
            action={
              <Button
                size="sm"
                variant="secondary"
                icon={<Plus className="w-4 h-4" />}
                onClick={onAddBlock}
                disabled={!!workflow.sealed}
                title={workflow.sealed ? t.workflows.sealedLocked : undefined}
              >
                {t.workflows.addBlock}
              </Button>
            }
          />
        </div>
        {workflow.blocks.length === 0 ? (
          <div className="px-5 py-12 text-center text-sm text-text-tertiary">
            {t.workflows.noBlocks}
          </div>
        ) : (
          // A sealed definition is read-only: no drag, edit or remove
          <fieldset disabled={!!workflow.sealed} className="min-w-0" title={workflow.sealed ? t.workflows.sealedLocked : undefined}>
            <SortableBlockList
              flashBlock={hoverFlash?.target === 'list' ? hoverFlash : null}
              onHoverBlock={bpmnModule ? (index) => setHoverFlash({ target: 'diagram', index, nonce: Date.now(), scroll: false }) : undefined}
              workflowId={workflow.id}
              blocks={workflow.blocks}
              onEditBlock={workflow.sealed ? () => {} : onEditBlock}
              onRemoveBlock={onRemoveBlock}
              locked={!!workflow.sealed}
            />
          </fieldset>
        )}
      </Card>

      {bpmnModule && workflow.blocks.length > 0 && (
        <section aria-label={t.workflows.diagramTab} ref={diagramRef}>
          <h2 className="text-sm font-semibold text-text mb-2">{t.workflows.diagramTab}</h2>
          <Suspense fallback={<div className="p-6 text-sm text-text-secondary">{t.common.loading}</div>}>
            <WorkflowDiagramTab
              workflowId={workflow.id}
              workflowName={workflow.name}
              definitionText={definitionText}
              lineRanges={definitionLineRanges}
              onSelectBlock={onSelectFromDiagram}
              flashBlock={hoverFlash?.target === 'diagram' ? hoverFlash : null}
              onHoverBlock={(index) => setHoverFlash({ target: 'list', index, nonce: Date.now(), scroll: false })}
            />
          </Suspense>
        </section>
      )}
    </>
  );
}
