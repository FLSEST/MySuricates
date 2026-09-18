import { createContext, useContext } from 'react';

/**
 * Run a step card belongs to. Pages that show the steps of more than one run wrap each run's cards
 * (`<StepAnchorScope value={runId}>`); their anchors then stay unique instead of colliding on `step-N`.
 * Empty (the default) keeps the plain `step-N` ids that deep links from outside the app use.
 */
export const StepAnchorScope = createContext<string>('');

/** Builds the DOM id of a step card and of links to it — `step-<index>`, run-prefixed inside a scope. */
export function useStepAnchor(): (stepIndex: number) => string {
  const runId = useContext(StepAnchorScope);
  return (stepIndex: number) => (runId ? `run-${runId}-step-${stepIndex}` : `step-${stepIndex}`);
}
