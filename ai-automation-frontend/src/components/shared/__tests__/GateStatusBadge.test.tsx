import { afterEach, describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { GateStatusBadge } from '../GateStatusBadge';

afterEach(() => useI18nStore.setState({ locale: 'en' }));

const run = (metadata: Record<string, unknown>) => ({ metadata });

describe('GateStatusBadge', () => {
  // 6c94dc62: fixed green/yellow/red palette classes ignored the theme
  it.each([
    ['GREEN', 'Passed', 'success'],
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

  // 17eb7994: the labels were fixed English
  it('labels the gate in the UI language', () => {
    useI18nStore.setState({ locale: 'de' });
    const { rerender } = render(<GateStatusBadge run={run({ gate_status: 'GREEN' })} />);
    expect(screen.getByText(de.insights.gateGreen)).toBeInTheDocument();
    rerender(<GateStatusBadge run={run({ gate_status: 'RED' })} />);
    expect(screen.getByText(de.insights.gateRed)).toBeInTheDocument();
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
