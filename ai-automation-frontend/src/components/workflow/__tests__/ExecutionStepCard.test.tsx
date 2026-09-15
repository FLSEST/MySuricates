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

  it('shows a prominent compression panel with source link and stage table', () => {
    const { container } = render(<ExecutionStepCard step={step({
      stepIndex: 4, blockType: 'compress',
      output: '## backend_tests — FAILED · exit 1\nStufen: lines.filter\n\nkurz',
      metadata: {
        derivedOutput: true,
        compression: {
          source: { block: 'backend_tests', stepIndex: 0, status: 'FAILED', exitCode: 1 },
          inChars: 1000, outChars: 250, maxChars: 20000, capped: true,
          stages: [{ op: 'lines.filter', inChars: 1000, outChars: 250, note: '−12 Zeilen' }],
        },
      },
    })} />);

    const panel = screen.getByTestId('compression-panel');
    expect(panel).toHaveTextContent('compressed 1,000 → 250 chars (−75 %)');
    expect(panel).toHaveTextContent('cut to the limit');
    expect(screen.getByRole('link', { name: /Source: backend_tests \(FAILED · exit 1\)/ })).toHaveAttribute('href', '#step-0');
    expect(screen.getByText('lines.filter')).toBeInTheDocument();
    expect(screen.getByText('−12 Zeilen')).toBeInTheDocument();
    expect(container.querySelector('#step-4')).not.toBeNull();
  });

  it('shows growth as a plus percentage, never a double sign', () => {
    render(<ExecutionStepCard step={step({
      stepIndex: 2, blockType: 'compress',
      output: '## feed — COMPLETED\nStufen: json.select\n\n{"a":100.0}',
      metadata: {
        derivedOutput: true,
        compression: {
          source: { block: 'feed', stepIndex: 0, status: 'COMPLETED', exitCode: null },
          inChars: 200, outChars: 250, maxChars: 20000, capped: false, stages: [],
        },
      },
    })} />);

    const panel = screen.getByTestId('compression-panel');
    expect(panel).toHaveTextContent('compressed 200 → 250 chars (+25 %)');
    expect(panel.textContent).not.toContain('−-');
  });
});
