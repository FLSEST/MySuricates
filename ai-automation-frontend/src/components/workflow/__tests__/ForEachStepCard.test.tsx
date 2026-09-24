import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ForEachStepCard } from '../ForEachStepCard';
import type { StepResult } from '../../../types';
import type { ForEachState } from '../../../types/websocket';

function step(metadata: Record<string, unknown> | undefined): StepResult {
  return {
    id: 's1', stepIndex: 2, blockType: 'foreach', input: 'foreach item in [2 items]', output: '[a]: ok\n[b]: ok',
    status: 'COMPLETED', durationMs: 1200, error: null, metadata,
  } as StepResult;
}

describe('ForEachStepCard', () => {
  it('renders provider/model pills and persisted items without live state', () => {
    render(<ForEachStepCard step={step({
      provider: 'CLAUDE', model: 'claude-opus-4-6', requestedModel: 'opus', totalItems: 2,
      items: [
        { index: 0, item: 'a', status: 'COMPLETED', durationMs: 5 },
        { index: 1, item: 'b', status: 'FAILED', durationMs: 7 },
      ],
    })} />);

    expect(screen.getByText('provider=CLAUDE')).toBeInTheDocument();
    expect(screen.getByText('model=claude-opus-4-6')).toBeInTheDocument();
    expect(screen.getByText('requested=opus')).toBeInTheDocument();
    expect(screen.getByText('2/2 items')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Show 2 items'));
    expect(screen.getByText('a')).toBeInTheDocument();
    expect(screen.getByText('b')).toBeInTheDocument();
    expect(screen.getByText('failed')).toBeInTheDocument();
  });

  it('prefers the live WebSocket state over persisted metadata', () => {
    const live: ForEachState = {
      stepIndex: 2, totalItems: 3, completedItems: 1,
      items: [{ itemIndex: 0, item: 'live-a', status: 'completed' }],
    };
    render(<ForEachStepCard step={step({ totalItems: 2, items: [{ index: 0, item: 'stale', status: 'COMPLETED' }] })} forEachState={live} />);

    expect(screen.getByText('1/3 items')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Show 1 items'));
    expect(screen.getByText('live-a')).toBeInTheDocument();
    expect(screen.queryByText('stale')).not.toBeInTheDocument();
  });

  it('shows only the final output when nothing is persisted', () => {
    render(<ForEachStepCard step={step(undefined)} />);
    expect(screen.queryByText(/items$/)).not.toBeInTheDocument();
    expect(screen.getByText(/\[a\]: ok/)).toBeInTheDocument();
  });
});
