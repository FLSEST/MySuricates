import { useMemo, useState } from 'react';
import { latestStepByBlock } from './stepMetadata';
import type { DiagramSelection, RunStatus } from './bpmnLayout';
import type { StepResult } from '../../types';

/** How long a step stays highlighted after a click on its box in the diagram. */
const STEP_HIGHLIGHT_MS = 1800;

/**
 * Connects the flow diagram and the step list of the run page: `statusByBlock` colors the boxes
 * (running, done, failed), `selectStep` scrolls to the step of the block when a box is clicked
 * and briefly highlights it (`stepHighlight`). A block that has not run yet stays silent.
 */
export function useRunDiagramSync(steps: StepResult[] | undefined, runActive = true) {
  // Diagram boxes are numbered by block position, steps by stepIndex — which runs ahead after every
  // wrapper block (foreach, while, …), hence the mapping via latestStepByBlock.
  const stepByBlock = useMemo(() => latestStepByBlock(steps ?? []), [steps]);
  const statusByBlock = useMemo(() => {
    const map = new Map<number, RunStatus>();
    for (const [blockIndex, step] of stepByBlock) {
      // A step waiting for a person is the active block — as long as the run still runs
      const s = step.status === 'RUNNING' || (step.status === 'WAITING_APPROVAL' && runActive) ? 'running' : step.status === 'COMPLETED' ? 'completed' : step.status === 'FAILED' ? 'failed' : undefined;
      if (s) map.set(blockIndex, s);
    }
    return map;
  }, [stepByBlock, runActive]);

  // Click in the diagram scrolls to the step of the block and briefly highlights it
  const [stepHighlight, setStepHighlight] = useState<{ index: number; nonce: number } | null>(null);
  const selectStep = (sel: DiagramSelection) => {
    const step = stepByBlock.get(sel.blockIndex);
    if (!step) return; // block has not run (yet)
    document.getElementById(`exec-step-${step.stepIndex}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setStepHighlight((prev) => ({ index: step.stepIndex, nonce: (prev?.nonce ?? 0) + 1 }));
    window.setTimeout(() => setStepHighlight(null), STEP_HIGHLIGHT_MS);
  };

  return { statusByBlock, stepHighlight, selectStep };
}
