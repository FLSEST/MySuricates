import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { FactoryFlowDiagram } from '../FactoryFlowDiagram';
import { activeParts, buildTimeline, frameAt, timelineDuration } from '../factoryFlow';
import { useI18nStore } from '../../../i18n';

function nodeActive(container: HTMLElement, id: string) {
  return container.querySelector(`[data-node="${id}"]`)?.getAttribute('data-active');
}

function edgeActive(container: HTMLElement, id: string) {
  return container.querySelector(`[data-edge="${id}"]`)?.getAttribute('data-active');
}

describe('factoryFlow', () => {
  it('create mode only generates and has no animation', () => {
    const parts = activeParts({ mode: 'create', autoFix: true, autoPromote: true });
    expect([...parts]).toEqual(['generate']);
    expect(buildTimeline({ mode: 'create', autoFix: true, autoPromote: true })).toEqual([]);
  });

  it('create & test without switches skips repair and template', () => {
    const parts = activeParts({ mode: 'createAndTest', autoFix: false, autoPromote: false });
    expect(parts.has('test')).toBe(true);
    expect(parts.has('repair')).toBe(false);
    expect(parts.has('template')).toBe(false);
    expect(buildTimeline({ mode: 'createAndTest', autoFix: false, autoPromote: false }).map(l => l.part))
      .toEqual(['generate', 'sync', 'test', 'pass', 'result']);
  });

  it('with auto-fix the story fails once, repairs and tests again', () => {
    const legs = buildTimeline({ mode: 'createAndTest', autoFix: true, autoPromote: true });
    expect(legs.map(l => l.part)).toEqual([
      'generate', 'sync', 'test', 'fail', 'repair', 'retry', 'test', 'pass', 'result', 'promote', 'template',
    ]);
    expect(legs.map(l => l.step)).toEqual([0, 1, 2, 3, 3, 3, 2, 4, 4, 4, 4]);
  });

  it('frameAt reports the current part, step and whether the token sits in a node', () => {
    const legs = buildTimeline({ mode: 'createAndTest', autoFix: true, autoPromote: false });
    const startOf = (i: number) => legs.slice(0, i).reduce((sum, l) => sum + l.seconds, 0);
    const repairIdx = legs.findIndex(l => l.part === 'repair');
    const inRepair = frameAt(legs, startOf(repairIdx) + 0.1);
    expect(inRepair).toMatchObject({ part: 'repair', step: 3, inNode: true });
    const onSync = frameAt(legs, startOf(1) + legs[1]!.seconds / 2);
    expect(onSync).toMatchObject({ part: 'sync', step: 1, inNode: false });
    expect(frameAt(legs, 0)).toMatchObject({ part: 'generate', step: 0 });
    expect(frameAt(legs, timelineDuration(legs) + 5)).toMatchObject({ part: 'result' });
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
    expect(container.querySelector('[data-node="generate"]')?.getAttribute('data-current')).toBe('true');
    expect(screen.queryByRole('button', { name: 'Play flow' })).toBeNull();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('highlights the first step before anything has played', () => {
    render(<FactoryFlowDiagram autoFix autoPromote maxRetries={2} testHealthy />);
    const items = screen.getAllByRole('listitem');
    expect(items[0]).toHaveAttribute('aria-current', 'step');
    expect(items[1]).not.toHaveAttribute('aria-current');
  });

  it('waits for "Play" under reduced motion and toggles to "Pause"', () => {
    const original = window.matchMedia;
    window.matchMedia = ((query: string) => ({
      matches: query.includes('reduce'), media: query,
      addEventListener: () => {}, removeEventListener: () => {},
    })) as unknown as typeof window.matchMedia;
    try {
      render(<FactoryFlowDiagram autoFix autoPromote maxRetries={2} testHealthy />);
      fireEvent.click(screen.getByRole('button', { name: 'Play flow' }));
      expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument();
    } finally {
      window.matchMedia = original;
    }
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
