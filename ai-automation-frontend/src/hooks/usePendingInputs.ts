import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';

/** A run waiting for an answer — what GET /executions/pending-input returns per entry. */
export interface PendingInput {
  runId: string;
  /** null when the workflow has since been deleted */
  workflowId: string | null;
  /** the name recorded when the run started */
  workflowName: string | null;
  type: 'approval' | 'human-input';
  blockId: string;
  /** resolved question or prompt, Markdown */
  question: string;
  options: string[];
  /** the options that count as a rejection — decided by the backend, never rebuilt here */
  rejectOptions: string[];
  stepIndex: number;
  requestedAt: string;
  /** when the waiting block gives up; null when unknown */
  timeoutAt: string | null;
}

export const PENDING_INPUTS_KEY = ['pending-input'] as const;

/**
 * Runs waiting for an answer, across all workflows, oldest first. Polled every 10 s from the sidebar:
 * the backend answers from memory and only queries the database when something waits.
 */
export function usePendingInputs() {
  return useQuery({
    queryKey: PENDING_INPUTS_KEY,
    queryFn: () => api.get<PendingInput[]>('/executions/pending-input'),
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
  });
}

/** Whether an option is a rejection — the backend's list when it sent one, otherwise the old "No" convention. */
export function isRejectOption(option: string, rejectOptions: string[] | undefined): boolean {
  if (rejectOptions) return rejectOptions.includes(option);
  return option.toLowerCase() === 'no';
}
