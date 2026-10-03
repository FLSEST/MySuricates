import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { en } from '../../../i18n/en';
import { TriggerSourceChip } from '../TriggerSourceChip';
import { RunProvenance } from '../RunProvenance';
import type { WorkflowRun } from '../../../types';

describe('TriggerSourceChip', () => {
  it('names what started the run', () => {
    render(<TriggerSourceChip source="SCHEDULE" />);

    expect(screen.getByTestId('trigger-source')).toHaveTextContent(`${en.workflows.triggeredBy}${en.workflows.triggerSources.SCHEDULE}`);
  });

  it('names the automatic resume after a restart or standby (TODO 90d10ba1)', () => {
    render(<TriggerSourceChip source="AUTO_RESUME" />);

    expect(screen.getByTestId('trigger-source')).toHaveTextContent(en.workflows.triggerSources.AUTO_RESUME);
    expect(en.workflows.triggerSources.AUTO_RESUME).not.toBe(en.workflows.triggerSources.RESUME);
  });

  it('renders nothing while the source is unknown', () => {
    const { container } = render(<TriggerSourceChip source={null} />);

    expect(container).toBeEmptyDOMElement();
  });
});

describe('RunProvenance', () => {
  const run: WorkflowRun = {
    id: 'run-1', workflowId: 'w1', workflowName: 'Audit', projectName: null, projectPath: null,
    status: 'COMPLETED', steps: [], startedAt: '2026-09-22T07:30:00Z', completedAt: '2026-09-22T07:35:00Z',
    durationMs: 300_000, userRating: null, error: null, metadata: {},
    triggerSource: 'CHAIN', sealed: false,
  };

  it('shows the trigger through the shared chip', () => {
    render(<RunProvenance run={run} />);

    expect(screen.getByTestId('run-provenance')).toContainElement(screen.getByTestId('trigger-source'));
    expect(screen.getByTestId('trigger-source')).toHaveTextContent(en.workflows.triggerSources.CHAIN);
  });

  // 3a35ced1: the run origin leads to the schedule that started the run
  it('links the trigger to its schedule when a schedule started the run', () => {
    renderWithProviders(<RunProvenance run={{ ...run, triggerSource: 'SCHEDULE', triggeredByScheduleId: 's-1' }} />);

    expect(screen.getByTestId('trigger-source')).toHaveAttribute('href', '/schedules?schedule=s-1');
  });

  it('leaves the chip out for a run without recorded trigger', () => {
    render(<RunProvenance run={{ ...run, triggerSource: null }} />);

    expect(screen.queryByTestId('trigger-source')).toBeNull();
    expect(screen.getByTestId('run-not-sealed')).toBeInTheDocument();
  });
});
