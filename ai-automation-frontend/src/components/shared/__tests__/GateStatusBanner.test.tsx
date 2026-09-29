import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { GateStatusBanner } from '../GateStatusBanner';
import { useI18nStore } from '../../../i18n';
import type { WorkflowRun } from '../../../types';

function buildRun(metadata: Record<string, unknown> | undefined): WorkflowRun {
  return {
    id: 'run-1',
    workflowId: 'wf-1',
    status: 'COMPLETED',
    startedAt: new Date().toISOString(),
    metadata,
  } as unknown as WorkflowRun;
}

describe('GateStatusBanner', () => {
  beforeEach(() => {
    useI18nStore.setState({ locale: 'en' });
  });

  it('renders nothing when gate_status is missing', () => {
    const { container } = render(<GateStatusBanner run={buildRun(undefined)} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing for unknown gate_status value', () => {
    const { container } = render(<GateStatusBanner run={buildRun({ gate_status: 'BLUE' })} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders GREEN banner with success title', () => {
    render(<GateStatusBanner run={buildRun({ gate_status: 'GREEN' })} />);
    expect(screen.getByText('Quality gate passed')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveAttribute('data-gate-status', 'GREEN');
  });

  it('renders YELLOW banner with findings title', () => {
    render(<GateStatusBanner run={buildRun({ gate_status: 'YELLOW' })} />);
    expect(screen.getByText('Quality gate findings')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveAttribute('data-gate-status', 'YELLOW');
  });

  it('renders RED banner with failed title', () => {
    render(<GateStatusBanner run={buildRun({ gate_status: 'RED' })} />);
    expect(screen.getByText('Quality gate failed')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveAttribute('data-gate-status', 'RED');
  });

  it('shows description and category when present', () => {
    render(
      <GateStatusBanner
        run={buildRun({
          gate_status: 'RED',
          gate_description: 'Coverage dropped below 80%.',
          gate_category: 'quality-gate-failure',
        })}
      />
    );
    expect(screen.getByText('Coverage dropped below 80%.')).toBeInTheDocument();
    expect(screen.getByText(/quality-gate-failure/)).toBeInTheDocument();
    expect(screen.getByText(/Category/)).toBeInTheDocument();
  });

  it('omits description block when only status is set', () => {
    render(<GateStatusBanner run={buildRun({ gate_status: 'GREEN' })} />);
    expect(screen.queryByText(/Category/)).toBeNull();
  });

  // 19ebb8a5: status tokens instead of fixed palette classes
  it.each([
    ['GREEN', 'status', 'success'],
    ['YELLOW', 'alert', 'warning'],
    ['RED', 'alert', 'danger'],
  ] as const)('colours the %s banner with the %s token', (gate, role, token) => {
    render(<GateStatusBanner run={buildRun({ gate_status: gate })} />);
    const banner = screen.getByRole(role);
    expect(banner).toHaveClass(`bg-${token}-subtle`, `border-${token}/30`);
    expect(banner.querySelector('.text-sm.font-semibold')).toHaveClass(`text-${token}`);
    expect(banner.className).not.toMatch(/-(green|yellow|red)-\d/);
  });

  it('uses German labels when locale is de', () => {
    useI18nStore.setState({ locale: 'de' });
    render(<GateStatusBanner run={buildRun({ gate_status: 'RED' })} />);
    expect(screen.getByText('Qualitäts-Gate fehlgeschlagen')).toBeInTheDocument();
  });
});
