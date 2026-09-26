import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { GateStatusBadge } from '../GateStatusBadge';

const run = (metadata: Record<string, unknown>) => ({ metadata });

describe('GateStatusBadge', () => {
  // 6c94dc62: fixed green/yellow/red palette classes ignored the theme
  it.each([
    ['GREEN', 'OK', 'success'],
    ['YELLOW', 'Findings', 'warning'],
    ['RED', 'Critical', 'danger'],
  ])('colours %s with the %s status tokens', (gate, label, token) => {
    const { container } = render(<GateStatusBadge run={run({ gate_status: gate })} />);

    const badge = screen.getByText(label);
    expect(badge).toHaveClass(`bg-${token}-subtle`, `text-${token}`);
    expect(badge.querySelector('span')).toHaveClass(`bg-${token}`);
    expect(container.querySelector('[class*="green-"], [class*="yellow-"], [class*="red-"]')).toBeNull();
  });

  it('shows the gate description, shortened, and its category as tooltip', () => {
    const description = 'Two findings in the dependency audit that need a look before the release';
    render(<GateStatusBadge run={run({ gate_status: 'YELLOW', gate_description: description, gate_category: 'security' })} />);

    const badge = screen.getByText(`${description.substring(0, 40)}…`);
    expect(badge).toHaveAttribute('title', `${description} (security)`);
  });

  it('keeps the short label when compact, and renders nothing without a known gate status', () => {
    const { rerender, container } = render(
      <GateStatusBadge run={run({ gate_status: 'RED', gate_description: 'Build broken' })} compact />,
    );
    expect(screen.getByText('Critical')).toBeInTheDocument();

    rerender(<GateStatusBadge run={run({ gate_status: 'PURPLE' })} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<GateStatusBadge run={run({})} />);
    expect(container).toBeEmptyDOMElement();
  });
});
