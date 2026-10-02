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
  /**
   * Changes whenever the socket announces a question without its content – an agentic approval only sends
   * `agentic_approval_required`, step and status stay the same (5b08cf33).
   */
  inputSignal?: number;
}

/** Questions of one run that the page no longer shows: answered for good, or closed without an answer. */
interface Closed {
  runId: string | undefined;
  answered: ReadonlySet<string>;
  dismissed: ReadonlySet<string>;
}

const NONE: ReadonlySet<string> = new Set();

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
 * the first query but before the socket subscribes otherwise never shows), and when the socket announces an agentic
 * approval (5b08cf33).
 *
 * `markAnswered` closes a question for good, at once: the socket keeps reporting it until its `approval_resolved`
 * arrives, and a query already under way may still return it. `dismiss` closes an approval without an answer — the
 * run still waits for it, so `dismissedApproval` names it while it is open and `reopen` shows the dialog again.
 */
export function useRunPendingInput(runId: string | undefined, run: WorkflowRun | undefined, live: LiveRunInput) {
  const active = run?.status === 'RUNNING' || run?.status === 'PAUSED';
  const { recoveredApproval, recoveredHumanInput } = useRunInputRecovery(
    active ? runId : undefined, active ? `${live.connected}|${progressSignal(run)}|${live.inputSignal ?? 0}` : undefined);

  const [closed, setClosed] = useState<Closed>({ runId, answered: NONE, dismissed: NONE });
  const answered = closed.runId === runId ? closed.answered : NONE;
  const dismissed = closed.runId === runId ? closed.dismissed : NONE;
  const unanswered = <T extends OpenInput>(input: T | null): T | null =>
    input && !answered.has(inputKey(input)) ? input : null;

  // A dismissed approval stays the open one — the query must not bring the same question back as a dialog
  const openApproval = unanswered(live.approval) ?? unanswered(recoveredApproval);
  const approvalDismissed = !!openApproval && dismissed.has(inputKey(openApproval));

  const change = (edit: (next: { answered: Set<string>; dismissed: Set<string> }) => void) => setClosed((prev) => {
    const own = prev.runId === runId;
    const next = { answered: new Set(own ? prev.answered : []), dismissed: new Set(own ? prev.dismissed : []) };
    edit(next);
    return { runId, ...next };
  });

  return {
    approval: approvalDismissed ? null : openApproval,
    humanInput: unanswered(live.humanInput) ?? unanswered(recoveredHumanInput),
    /** An approval closed without an answer that the run still waits for. */
    dismissedApproval: approvalDismissed ? openApproval : null,
    markAnswered: (input: OpenInput) => change((next) => next.answered.add(inputKey(input))),
    dismiss: (approval: PendingApproval) => change((next) => next.dismissed.add(inputKey(approval))),
    reopen: () => change((next) => next.dismissed.clear()),
  };
}
