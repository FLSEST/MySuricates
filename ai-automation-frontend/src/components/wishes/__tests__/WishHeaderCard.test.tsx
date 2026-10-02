import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import type { Wish } from '../../../types';
import { WishHeaderCard } from '../WishHeaderCard';

beforeEach(() => useI18nStore.setState({ locale: 'en' }));

function failedWish(overrides: Partial<Wish>): Wish {
  return {
    id: 'wish-1', originalText: 'Build a weekly report', status: 'FAILED', projectId: 'p1', projectName: 'Alpha',
    decomposition: null, generatedWorkflowIds: [], generatedWorkflowNames: [], referenceWorkflowIds: [],
    referenceWorkflowNames: [], generatedChainId: null, iterationCount: 0, maxIterations: 3, currentRunId: null,
    resultSummary: null, error: 'boom', metadata: {}, createdAt: '2026-09-20T10:00:00Z', updatedAt: '2026-09-20T10:00:00Z',
    autoRefine: false, iterationHistory: [], ...overrides,
  };
}

/** Colour of each timeline bar, left to right: received, decomposing, executing, evaluating, completed. */
function timeline() {
  const labels = screen.getByText(en.wishes.status.received).parentElement!;
  const bars = [...labels.previousElementSibling!.querySelectorAll('.rounded-full')];
  return {
    bars: bars.map((bar) => bar.className.match(/bg-(success|danger|border|accent|warning)/)?.[1] + (bar.className.includes('animate-pulse') ? '~' : '')),
    labels: [...labels.children].map((label) => label.textContent),
  };
}

function renderCard(wish: Wish) {
  renderWithProviders(<WishHeaderCard wish={wish} onCancel={vi.fn()} onDelete={vi.fn()} />);
}

describe('WishHeaderCard timeline of a failed wish', () => {
  it('marks the decomposition when the wish failed before its first iteration was recorded', () => {
    renderCard(failedWish({}));
    expect(timeline().bars).toEqual(['success', 'danger', 'border', 'border', 'border']);
    expect(timeline().labels).toEqual(['Received', 'Failed', 'Executing', 'Evaluating', 'Completed']);
    expect(screen.getByText(en.wishes.status.failed)).toHaveClass('text-danger');
  });

  it('marks the decomposition when its workflows were never finished — the iteration counter did not advance', () => {
    renderCard(failedWish({ iterationCount: 1, iterationHistory: [
      { iteration: 1, runId: 'run-1', evaluation: { satisfied: false, shouldRetry: true } },
      { iteration: 2, decomposition: { analysis: 'x' } },
    ] }));
    expect(timeline().bars).toEqual(['success', 'danger', 'border', 'border', 'border']);
  });

  it('marks the execution when the decomposition finished but no run started', () => {
    renderCard(failedWish({ iterationCount: 1, iterationHistory: [{ iteration: 1, workflowIds: ['wf-1'] }] }));
    expect(timeline().bars).toEqual(['success', 'success', 'danger', 'border', 'border']);
  });

  it('marks the execution when the run of the newest iteration never got evaluated', () => {
    renderCard(failedWish({ iterationCount: 1, iterationHistory: [{ iteration: 1, workflowIds: ['wf-1'], runId: 'run-1' }] }));
    expect(timeline().bars).toEqual(['success', 'success', 'danger', 'border', 'border']);
    expect(timeline().labels).toEqual(['Received', 'Decomposing', 'Failed', 'Evaluating', 'Completed']);
  });

  it('marks the evaluation when the newest iteration was evaluated', () => {
    renderCard(failedWish({ iterationCount: 3, iterationHistory: [
      { iteration: 3, workflowIds: ['wf-1'], runId: 'run-3', evaluation: { satisfied: false, issues: ['No chart'] } },
    ] }));
    expect(timeline().bars).toEqual(['success', 'success', 'success', 'danger', 'border']);
  });

  it('leaves the other states as they were', () => {
    renderCard(failedWish({ status: 'EXECUTING', error: null }));
    expect(timeline().bars).toEqual(['success', 'success', 'accent~', 'border', 'border']);
    expect(screen.queryByText(en.wishes.status.failed)).toBeNull();
  });
});

/** MySuricates-TODO 92bffe64: the backend records where a wish stopped; old backends send no stoppedPhase. */
describe('WishHeaderCard timeline with the recorded stop phase', () => {
  it('trusts the recorded phase over the history guess — a refinement that failed before its entry', () => {
    const history = [{ iteration: 1, workflowIds: ['wf-1'], runId: 'run-1', evaluation: { satisfied: false } }];
    renderCard(failedWish({ iterationCount: 1, iterationHistory: history }));
    expect(timeline().bars).toEqual(['success', 'success', 'success', 'danger', 'border']);

    document.body.innerHTML = '';
    renderCard(failedWish({ iterationCount: 1, iterationHistory: history, stoppedPhase: 'DECOMPOSING' }));
    expect(timeline().bars).toEqual(['success', 'danger', 'border', 'border', 'border']);
  });

  it('places a waiting phase where it waits', () => {
    renderCard(failedWish({ stoppedPhase: 'AWAITING_APPROVAL', iterationCount: 1 }));
    expect(timeline().bars).toEqual(['success', 'success', 'success', 'danger', 'border']);
  });

  it('shows where a cancelled wish stopped instead of an empty timeline', () => {
    renderCard(failedWish({ status: 'CANCELLED', error: null, stoppedPhase: 'EXECUTING' }));
    expect(timeline().bars).toEqual(['success', 'success', 'warning', 'border', 'border']);
    expect(screen.getByText(en.wishes.status.cancelled)).toHaveClass('text-warning');
  });

  it('guesses for a cancelled wish from an old backend, never an empty timeline', () => {
    renderCard(failedWish({ status: 'CANCELLED', error: null, iterationCount: 1,
      iterationHistory: [{ iteration: 1, workflowIds: ['wf-1'], runId: 'run-1' }] }));
    expect(timeline().bars).toEqual(['success', 'success', 'warning', 'border', 'border']);
  });

  it('shows a completed wish as done throughout, without a pulsing bar', () => {
    renderCard(failedWish({ status: 'COMPLETED', error: null, stoppedPhase: null }));
    expect(timeline().bars).toEqual(['success', 'success', 'success', 'success', 'success']);
  });

  it('keeps a refining wish on the timeline (decomposing anew)', () => {
    renderCard(failedWish({ status: 'REFINING', error: null, iterationCount: 1 }));
    expect(timeline().bars).toEqual(['success', 'accent~', 'border', 'border', 'border']);
  });
});
