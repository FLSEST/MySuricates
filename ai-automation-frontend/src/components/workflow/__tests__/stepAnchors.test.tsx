import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ExecutionStepCard } from '../ExecutionStepCard';
import { StepAnchorScope } from '../stepAnchors';
import type { StepResult } from '../../../types';

function step(id: string, stepIndex: number, overrides: Partial<StepResult> = {}): StepResult {
  return {
    id, stepIndex, input: 'echo hi', output: 'hi', status: 'COMPLETED',
    durationMs: 100, error: null, blockType: 'shell', ...overrides,
  };
}

function compressStep(id: string, stepIndex: number, sourceIndex: number): StepResult {
  return step(id, stepIndex, {
    blockType: 'compress',
    output: '## backend_tests — FAILED · exit 1\nStages: lines.filter\n\nkurz',
    metadata: {
      derivedOutput: true,
      compression: {
        source: { block: 'backend_tests', stepIndex: sourceIndex, status: 'FAILED', exitCode: 1 },
        inChars: 1000, outChars: 250, maxChars: 20000, capped: false, stages: [],
      },
    },
  });
}

function anchorIds(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('[data-step-kind]')).map((el) => el.id);
}

describe('step anchors', () => {
  it('gives every step card its own id when two runs are rendered on one page', () => {
    const { container } = render(
      <div>
        <StepAnchorScope value="run-a">
          <ExecutionStepCard step={step('a0', 0)} />
          <ExecutionStepCard step={step('a1', 1)} />
        </StepAnchorScope>
        <StepAnchorScope value="run-b">
          <ExecutionStepCard step={step('b0', 0)} />
          <ExecutionStepCard step={step('b1', 1)} />
        </StepAnchorScope>
      </div>,
    );

    const ids = anchorIds(container);
    expect(ids).toEqual(['run-run-a-step-0', 'run-run-a-step-1', 'run-run-b-step-0', 'run-run-b-step-1']);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('links a compression source to the anchor of the same run', () => {
    render(
      <StepAnchorScope value="run-a">
        <ExecutionStepCard step={compressStep('a1', 1, 0)} />
      </StepAnchorScope>,
    );

    expect(screen.getByRole('link', { name: /Source: backend_tests/ })).toHaveAttribute('href', '#run-run-a-step-0');
  });

  it('keeps the plain step-N anchors without a run scope', () => {
    const { container } = render(<ExecutionStepCard step={compressStep('x', 3, 2)} />);

    expect(anchorIds(container)).toEqual(['step-3']);
    expect(screen.getByRole('link', { name: /Source: backend_tests/ })).toHaveAttribute('href', '#step-2');
  });
});
