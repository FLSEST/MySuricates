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

  // a7ddf199: next to "Deterministic" and "Shell Command" the header says the step ran in a container
  it('shows the container and its image for a tool-mode step', () => {
    render(<ExecutionStepCard step={step({ metadata: {
      blockType: 'shell', blockName: 'trivy', executionMode: 'container-tool:automation-agent-devops:latest',
    } })} />);
    const badge = screen.getByText('Container · automation-agent-devops:latest');
    expect(badge.closest('[title]')).toHaveAttribute('title', expect.stringContaining('own entrypoint'));
    expect(screen.getByText('Deterministic')).toBeInTheDocument();
    expect(screen.getByText('Shell Command')).toBeInTheDocument();
  });

  it('shows the container for a shell command that ran in one', () => {
    render(<ExecutionStepCard step={step({ metadata: { blockType: 'shell', executionMode: 'container:node:22' } })} />);
    expect(screen.getByText('Container · node:22').closest('[title]'))
      .toHaveAttribute('title', expect.stringContaining('Shell command ran in the container node:22'));
  });

  it('shows no container badge for a shell step on the host', () => {
    render(<ExecutionStepCard step={step({ metadata: { blockType: 'shell', executionMode: 'shell' } })} />);
    expect(screen.queryByText(/^Container ·/)).not.toBeInTheDocument();
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

  // 4eea7a87: the backend marks the step WAITING_APPROVAL while a person decides
  it('shows a waiting step with its own icon and label', () => {
    render(<ExecutionStepCard step={step({ blockType: 'approval', status: 'WAITING_APPROVAL', output: '' })} />);
    expect(screen.getByRole('img', { name: 'Waiting for you – approval or input pending' })).toHaveClass('text-human');
  });

  // An interrupted/failed/cancelled run no longer waits — the step must not ask for the user
  it('shows a step left waiting in an ended run as abandoned', () => {
    render(<ExecutionStepCard step={step({ blockType: 'approval', status: 'WAITING_APPROVAL', output: '' })} runActive={false} />);
    expect(screen.getByRole('img', { name: 'Run ended – the question was left unanswered' })).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: /Waiting for you/ })).not.toBeInTheDocument();
  });

  // 31b7aca8: the step note (config.description) reaches the run view via metadata
  it('shows the step note under the header', () => {
    render(<ExecutionStepCard step={step({ metadata: { blockType: 'shell', blockName: 'pruefen', blockDescription: 'Ersetze die Pruefung.' } })} />);

    expect(screen.getByTestId('step-note')).toHaveTextContent('Ersetze die Pruefung.');
  });

  it('shows no note line for a step without one', () => {
    render(<ExecutionStepCard step={step({ metadata: { blockType: 'shell' } })} />);

    expect(screen.queryByTestId('step-note')).toBeNull();
  });

  it('shows an unknown block type verbatim', () => {
    render(<ExecutionStepCard step={step({ blockType: 'future-block' })} />);
    expect(screen.getByText('future-block')).toBeInTheDocument();
  });

  it('shows a prominent compression panel with source link and stage table', () => {
    const { container } = render(<ExecutionStepCard step={step({
      stepIndex: 4, blockType: 'compress',
      output: '## backend_tests — FAILED · exit 1\nStages: lines.filter\n\nkurz',
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
      output: '## feed — COMPLETED\nStages: json.select\n\n{"a":100.0}',
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

  // dca0c03e: a shadow step nobody answered (timeout, default value) shows the proposal but no verdict
  it('shows agreement only when a person answered the shadow step', () => {
    const shadow = { blockType: 'approval', answeredBy: 'shadow', shadowAnswer: 'Ja' };
    const { rerender } = render(<ExecutionStepCard step={step({ metadata: { ...shadow, agreed: false } })} />);
    expect(screen.getByText('Person deviated from the AI proposal')).toBeInTheDocument();

    rerender(<ExecutionStepCard step={step({ status: 'FAILED', metadata: shadow })} />);
    expect(screen.queryByText('Person deviated from the AI proposal')).not.toBeInTheDocument();
    expect(screen.queryByText('Person and AI agreed')).not.toBeInTheDocument();
  });
});
