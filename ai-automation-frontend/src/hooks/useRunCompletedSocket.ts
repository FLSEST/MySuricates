import { useTopicSocket } from './useTopicSocket';

/** What the backend sends on /topic/dashboard/runs — ids and the status enum, nothing else. */
export interface RunCompletedMessage {
  type: 'run_completed';
  runId: string;
  workflowId: string;
  status: string;
}

const TOPIC = '/topic/dashboard/runs';

/**
 * Tells the dashboard that a run has ended, so the workflow widgets reload instead of polling every
 * 30 seconds per instance (spec E3). One connection per dashboard, only while workflow widgets are
 * shown. Returns whether the connection is up — the caller falls back to polling while it is not.
 */
export function useRunCompletedSocket(enabled: boolean, onRunCompleted: (message: RunCompletedMessage) => void): boolean {
  return useTopicSocket(TOPIC, enabled, (data) => {
    const message = data as Partial<RunCompletedMessage> | null;
    if (message?.type === 'run_completed' && typeof message.workflowId === 'string' && typeof message.runId === 'string') {
      onRunCompleted(message as RunCompletedMessage);
    }
  });
}
