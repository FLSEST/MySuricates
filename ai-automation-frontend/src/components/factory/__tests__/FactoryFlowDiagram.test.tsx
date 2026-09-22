import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { FactoryFlowDiagram } from '../FactoryFlowDiagram';
import { activeParts, tokenRoute, NODES } from '../factoryFlow';
import { useI18nStore } from '../../../i18n';

function nodeActive(container: HTMLElement, id: string) {
  return container.querySelector(`[data-node="${id}"]`)?.getAttribute('data-active');
}

function edgeActive(container: HTMLElement, id: string) {
  return container.querySelector(`[data-edge="${id}"]`)?.getAttribute('data-active');
}

describe('factoryFlow', () => {
  it('create mode only generates', () => {
    const parts = activeParts({ mode: 'create', autoFix: true, autoPromote: true });
    expect([...parts]).toEqual(['generate']);
    expect(tokenRoute({ mode: 'create', autoFix: true, autoPromote: true })).toBeNull();
  });

  it('create & test without switches skips repair and template', () => {
    const parts = activeParts({ mode: 'createAndTest', autoFix: false, autoPromote: false });
    expect(parts.has('test')).toBe(true);
    expect(parts.has('repair')).toBe(false);
    expect(parts.has('template')).toBe(false);
  });

  it('token route visits the test run twice when auto-fix is on', () => {
    const testCenter = [NODES.test.x + NODES.test.w / 2, NODES.test.y + NODES.test.h / 2];
    const count = (route: number[][] | null) =>
      route!.filter(([x, y]) => x === testCenter[0] && y === testCenter[1]).length;
    expect(count(tokenRoute({ mode: 'createAndTest', autoFix: false, autoPromote: false }) as number[][])).toBe(1);
    expect(count(tokenRoute({ mode: 'createAndTest', autoFix: true, autoPromote: false }) as number[][])).toBe(2);
  });
});

describe('FactoryFlowDiagram', () => {
  beforeEach(() => {
    localStorage.clear();
    useI18nStore.setState({ locale: 'en' });
  });

  it('defaults to create & test and states that only this mode tests', () => {
    const { container } = render(<FactoryFlowDiagram autoFix autoPromote={false} maxRetries={3} testHealthy />);
    expect(screen.getByRole('tab', { name: 'Create & Test' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText(/Only "Create & Test"/)).toBeInTheDocument();
    expect(nodeActive(container, 'repair')).toBe('true');
    expect(nodeActive(container, 'template')).toBe('false');
    expect(screen.getByText('at most 3×')).toBeInTheDocument();
    expect(screen.getByText(/at most 3 times/)).toBeInTheDocument();
  });

  it('dims the repair loop when auto-fix is off', () => {
    const { container } = render(<FactoryFlowDiagram autoFix={false} autoPromote maxRetries={2} testHealthy />);
    expect(nodeActive(container, 'repair')).toBe('false');
    expect(edgeActive(container, 'retry')).toBe('false');
    expect(nodeActive(container, 'template')).toBe('true');
    expect(screen.getByText(/Auto-fix is off/)).toBeInTheDocument();
  });

  it('create mode shows that nothing runs on the Test instance', () => {
    const { container } = render(<FactoryFlowDiagram autoFix autoPromote maxRetries={2} testHealthy />);
    fireEvent.click(screen.getByRole('tab', { name: 'Create Workflow' }));
    expect(screen.getByText(/runs step 1 only/)).toBeInTheDocument();
    expect(nodeActive(container, 'test')).toBe('false');
    expect(container.querySelector('[data-token="static"]')).not.toBeNull();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('warns when the Test instance is offline', () => {
    render(<FactoryFlowDiagram autoFix={false} autoPromote={false} maxRetries={2} testHealthy={false} />);
    expect(screen.getByText(/offline right now/)).toBeInTheDocument();
  });

  it('remembers the collapsed state', () => {
    const { unmount } = render(<FactoryFlowDiagram autoFix={false} autoPromote={false} maxRetries={2} testHealthy />);
    fireEvent.click(screen.getByRole('button', { name: /How the factory works/ }));
    expect(screen.queryByRole('tablist')).toBeNull();
    unmount();
    render(<FactoryFlowDiagram autoFix={false} autoPromote={false} maxRetries={2} testHealthy />);
    expect(screen.queryByRole('tablist')).toBeNull();
  });
});
