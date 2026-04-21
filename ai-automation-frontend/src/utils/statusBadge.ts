import type { BadgeTone } from '../components/palette/contexts/types';
import type { RunStatus, ProjectStatus } from '../types';

export function runStatusBadge(status: RunStatus): { label: string; tone: BadgeTone } {
  switch (status) {
    case 'COMPLETED':
      return { label: 'ok', tone: 'success' };
    case 'COMPLETED_WITH_ERRORS':
      return { label: 'errors', tone: 'warn' };
    case 'RUNNING':
      return { label: 'running', tone: 'info' };
    case 'PAUSED':
      return { label: 'paused', tone: 'warn' };
    case 'PENDING':
      return { label: 'pending', tone: 'neutral' };
    case 'FAILED':
      return { label: 'failed', tone: 'danger' };
    case 'CANCELLED':
      return { label: 'cancelled', tone: 'neutral' };
    case 'INTERRUPTED':
      return { label: 'interrupted', tone: 'warn' };
    default:
      return { label: String(status), tone: 'neutral' };
  }
}

export function projectStatusBadge(status: ProjectStatus): { label: string; tone: BadgeTone } {
  switch (status) {
    case 'RUNNING':
      return { label: 'running', tone: 'success' };
    case 'STARTING':
      return { label: 'starting', tone: 'info' };
    case 'ERROR':
      return { label: 'error', tone: 'danger' };
    case 'STOPPED':
    default:
      return { label: 'stopped', tone: 'neutral' };
  }
}
