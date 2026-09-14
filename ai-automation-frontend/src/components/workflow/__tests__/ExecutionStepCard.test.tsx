import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ExecutionStepCard } from '../ExecutionStepCard';
import type { StepResult } from '../../../types';

function step(overrides: Partial<StepResult>): StepResult {
  return {
    id: 's1', stepIndex: 0, input: 'echo hi', output: 'hi', status: 'COMPLETED',
    durationMs: 1200, error: null, ...overrides,
  };
}

function frame(container: HTMLElement) {
  return container.querySelector('[data-step-kind]');
}

describe('ExecutionStepCard', () => {
  it('marks a shell step as deterministic with its translated block type (API shape: type only in metadata)', () => {
    const { container } = render(
      <ExecutionStepCard step={step({ metadata: { blockType: 'shell', blockName: 'Build', exitCode: 0 } })} />,
    );
    expect(frame(container)).toHaveAttribute('data-step-kind', 'deterministic');
    expect(screen.getByText('Deterministic')).toBeInTheDocument();
    expect(screen.getByText('Shell Command')).toBeInTheDocument();
    expect(screen.getByText('Build')).toBeInTheDocument();
    expect(screen.getByText('Step 1')).toBeInTheDocument();
  });

  it('marks a step with an AI call as AI and keeps the provider/model pills', () => {
    const { container } = render(<ExecutionStepCard step={step({
      blockType: 'simple', stepIndex: 1,
      metadata: { provider: 'CLAUDE', model: 'opus', aiRequest: { provider: 'CLAUDE', model: 'opus' } },
    })} />);
    expect(frame(container)).toHaveAttribute('data-step-kind', 'ai');
    expect(screen.getByText('AI')).toBeInTheDocument();
    expect(screen.getByText('Simple Command')).toBeInTheDocument();
    expect(screen.getByText('provider=CLAUDE')).toBeInTheDocument();
    expect(screen.getByText('Step 2')).toBeInTheDocument();
  });

  it('marks an approval step as human', () => {
    const { container } = render(<ExecutionStepCard step={step({ blockType: 'approval' })} />);
    expect(frame(container)).toHaveAttribute('data-step-kind', 'human');
    expect(screen.getByText('Human')).toBeInTheDocument();
  });

  it('shows an unknown block type verbatim', () => {
    render(<ExecutionStepCard step={step({ blockType: 'future-block' })} />);
    expect(screen.getByText('future-block')).toBeInTheDocument();
  });
});
