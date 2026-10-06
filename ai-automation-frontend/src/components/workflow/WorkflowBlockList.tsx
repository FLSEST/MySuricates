import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import {
  Terminal,
  Sparkles,
  Brain,
  Repeat,
  GitBranch,
  RefreshCw,
  ShieldCheck,
  Layers,
  MessageSquare,
  Route,
  RotateCcw,
  Wrench,
} from 'lucide-react';
import { Spinner } from '../ui';
import { useWorkflowBpmn } from '../../hooks/useWorkflows';
import { useModuleEnabled } from '../../hooks/useModules';
import { useT, type TranslationKeys } from '../../i18n';
import type { BlockDefinition } from '../../types';
import { shellSummary } from './shellSummary';
import type { BlockFlash as DiagramBlockFlash, DiagramSelection } from './BpmnDiagramView';

const BpmnDiagramView = lazy(() => import('./BpmnDiagramView'));

type BlockFlash = DiagramBlockFlash | null;

function BlocksDiagram({
  workflowId,
  t,
  flashBlock,
  onSelectBlock,
}: {
  workflowId: string;
  t: TranslationKeys;
  flashBlock: BlockFlash;
  onSelectBlock: (selection: DiagramSelection) => void;
}) {
  const { data: xml, isLoading, isError } = useWorkflowBpmn(workflowId);
  if (isLoading) {
    return <div className="flex justify-center py-4"><Spinner /></div>;
  }
  if (isError || !xml) {
    return <div role="alert" className="py-2 text-xs text-text-secondary">{t.workflows.diagramLoadFailed}</div>;
  }
  return (
    <div className="rounded-lg bg-surface-raised border border-border p-2">
      <Suspense fallback={<div className="flex justify-center py-4"><Spinner /></div>}>
        <BpmnDiagramView xml={xml} flashBlock={flashBlock} onSelectBlock={onSelectBlock} />
      </Suspense>
    </div>
  );
}

const BLOCK_TYPE_ICONS: Record<string, React.ReactNode> = {
  shell: <Terminal className="w-3.5 h-3.5" />,
  simple: <Sparkles className="w-3.5 h-3.5" />,
  agentic: <Brain className="w-3.5 h-3.5" />,
  foreach: <Repeat className="w-3.5 h-3.5" />,
  conditional: <GitBranch className="w-3.5 h-3.5" />,
  while: <RefreshCw className="w-3.5 h-3.5" />,
  approval: <ShieldCheck className="w-3.5 h-3.5" />,
  'sub-workflow': <Layers className="w-3.5 h-3.5" />,
  'human-input': <MessageSquare className="w-3.5 h-3.5" />,
  switch: <Route className="w-3.5 h-3.5" />,
  retry: <RotateCcw className="w-3.5 h-3.5" />,
  'skill-capability': <Wrench className="w-3.5 h-3.5" />,
};

function getBlockLabel(block: { type: string; config: Record<string, unknown> }): string {
  const prompt = block.config.prompt as string | undefined;
  const command = block.type === 'shell' ? shellSummary(block.config) : (block.config.command as string | undefined);
  const label = prompt || command || '';
  return label.length > 60 ? label.slice(0, 60) + '…' : label;
}

/**
 * Blocks of a workflow in the panel of the workflow list, with the BPMN diagram below (module
 * `bpmn`). Cross-reference: a click on a list entry flashes its box in the diagram and vice versa.
 */
export function WorkflowBlockList({ workflowId, blocks }: { workflowId: string; blocks: BlockDefinition[] }) {
  const t = useT();
  const bpmnEnabled = useModuleEnabled('bpmn');
  // Cross-reference: a click in the diagram makes the list entry blink and vice versa
  // (bound to the workflow ID, so that switching the selection makes nothing blink afterwards)
  const [flash, setFlash] = useState<{ workflowId: string; target: 'list' | 'diagram'; index: number; nonce: number } | null>(null);
  const current = flash?.workflowId === workflowId ? flash : null;
  const listFlash: BlockFlash = current?.target === 'list' ? current : null;
  const diagramFlash: BlockFlash = current?.target === 'diagram' ? current : null;
  const blockRefs = useRef<(HTMLButtonElement | null)[]>([]);

  const listFlashIndex = listFlash?.index;
  const listFlashNonce = listFlash?.nonce;
  useEffect(() => {
    if (listFlashIndex === undefined) return;
    blockRefs.current[listFlashIndex]?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
  }, [listFlashIndex, listFlashNonce]);

  return (
    <div>
      <h3 className="text-sm font-semibold text-text mb-2">{t.workflows.blocks}</h3>
      <div className="space-y-1">
        {blocks.map((block, idx) => (
          <button
            key={idx}
            type="button"
            ref={(el) => { blockRefs.current[idx] = el; }}
            onClick={bpmnEnabled ? () => setFlash({ workflowId, target: 'diagram', index: idx, nonce: Date.now() }) : undefined}
            className={`relative flex w-full items-center gap-2 px-3 py-1.5 rounded-lg bg-surface-raised border border-border text-xs text-left ${
              bpmnEnabled ? 'hover:border-border-strong' : 'cursor-default'
            }`}
          >
            {listFlash?.index === idx && (
              <span
                key={listFlash.nonce}
                data-testid="block-list-flash"
                className="block-flash pointer-events-none absolute inset-0 rounded-lg bg-accent/20 ring-2 ring-accent"
              />
            )}
            <span className="text-text-tertiary shrink-0">
              {BLOCK_TYPE_ICONS[block.type] ?? <Sparkles className="w-3.5 h-3.5" />}
            </span>
            <span className="text-text-secondary font-medium shrink-0">{block.type}</span>
            {getBlockLabel(block) && (
              <span className="text-text-tertiary truncate font-mono">{getBlockLabel(block)}</span>
            )}
          </button>
        ))}
      </div>
      {bpmnEnabled && (
        <div className="mt-3">
          <p className="text-xs text-text-tertiary mb-1.5">{t.workflows.blocksDiagramHint}</p>
          <BlocksDiagram
            workflowId={workflowId}
            t={t}
            flashBlock={diagramFlash}
            onSelectBlock={(sel) => setFlash({ workflowId, target: 'list', index: sel.blockIndex, nonce: Date.now() })}
          />
        </div>
      )}
    </div>
  );
}
