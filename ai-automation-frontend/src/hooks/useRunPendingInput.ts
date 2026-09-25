import { useState } from 'react';
import type { PendingApproval } from './useWebSocket';
import type { PendingHumanInput, WorkflowRun } from '../types';
import { useRunInputRecovery } from './useRunInputRecovery';

/** What identifies one open question of a run: the step index is unique per run. */
interface OpenInput {
  blockId: string;
  stepIndex: number;
}

/** The live side of the run page: socket state and the dialogs its events opened. */
export interface LiveRunInput {
  connected: boolean;
  approval: PendingApproval | null;
  humanInput: PendingHumanInput | null;
}

const NOTHING_SETTLED: ReadonlySet<string> = new Set();

function inputKey(input: OpenInput): string {
  return `${input.stepIndex}:${input.blockId}`;
}

/** Status plus the newest step and its status — changes whenever the run moves on. */
function progressSignal(run: WorkflowRun): string {
  const latest = run.steps.reduce<WorkflowRun['steps'][number] | null>(
    (newest, step) => (!newest || step.stepIndex > newest.stepIndex ? step : newest), null);
  return `${run.status}:${latest?.stepIndex ?? -1}:${latest?.status ?? ''}`;
}

/**
 * The open approval or human input of a run page — one source for both dialogs. Socket events come first; the
 * query `GET /executions/{id}/approval` fills the gap while the run is running or paused, and is repeated when the
 * socket connects and when the run status or the current step changes (78af069c: an approval that starts after
 * the first query but before the socket subscribes otherwise never shows).
 *
 * `settle` closes a question at once, once it is answered or dismissed: the socket keeps reporting it until its
 * `approval_resolved` arrives, and a query already under way may still return it.
 */
export function useRunPendingInput(runId: string | undefined, run: WorkflowRun | undefined, live: LiveRunInput) {
  const active = run?.status === 'RUNNING' || run?.status === 'PAUSED';
  const { recoveredApproval, recoveredHumanInput } = useRunInputRecovery(
    active ? runId : undefined, active ? `${live.connected}|${progressSignal(run)}` : undefined);

  const [settled, setSettled] = useState<{ runId: string | undefined; keys: ReadonlySet<string> }>(
    { runId, keys: NOTHING_SETTLED });
  const settledKeys = settled.runId === runId ? settled.keys : NOTHING_SETTLED;
  const open = <T extends OpenInput>(input: T | null): T | null =>
    input && !settledKeys.has(inputKey(input)) ? input : null;

  const settle = (input: OpenInput) => setSettled((prev) => ({
    runId,
    keys: new Set([...(prev.runId === runId ? prev.keys : []), inputKey(input)]),
  }));

  return {
    approval: open(live.approval) ?? open(recoveredApproval),
    humanInput: open(live.humanInput) ?? open(recoveredHumanInput),
    settle,
  };
}
