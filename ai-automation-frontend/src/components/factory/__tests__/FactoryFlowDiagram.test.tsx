import { describe, it, expect, beforeEach, vi } from 'vitest';
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
  it('create mode only generates, then stops at "end, no test"', () => {
    const parts = activeParts({ mode: 'create', autoFix: true, autoPromote: true });
    expect([...parts]).toEqual(['generate']);
    const legs = buildTimeline({ mode: 'create', autoFix: true, autoPromote: true });
    expect(legs.map(l => [l.part, l.step])).toEqual([['generate', 0], ['generate', 1]]);
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

type Props = React.ComponentProps<typeof FactoryFlowDiagram>;

function renderDiagram(props: Partial<Props> = {}) {
  return render(
    <FactoryFlowDiagram
      autoFix={false} autoPromote={false} maxRetries={2} testHealthy
      onAutoFixChange={vi.fn()} onAutoPromoteChange={vi.fn()}
      {...props}
    />,
  );
}

describe('FactoryFlowDiagram', () => {
  beforeEach(() => {
    localStorage.clear();
    useI18nStore.setState({ locale: 'en' });
  });

  it('defaults to create & test and states that only this mode tests', () => {
    const { container } = renderDiagram({ autoFix: true, autoPromote: false, maxRetries: 3, testHealthy: true });
    expect(screen.getByRole('tab', { name: 'Create & Test' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText(/Only "Create & Test"/)).toBeInTheDocument();
    expect(nodeActive(container, 'repair')).toBe('true');
    expect(nodeActive(container, 'template')).toBe('false');
    expect(screen.getByText('at most 3×')).toBeInTheDocument();
    expect(screen.getByText(/at most 3 times/)).toBeInTheDocument();
  });

  it('dims the repair loop when auto-fix is off', () => {
    const { container } = renderDiagram({ autoFix: false, autoPromote: true, maxRetries: 2, testHealthy: true });
    expect(nodeActive(container, 'repair')).toBe('false');
    expect(edgeActive(container, 'retry')).toBe('false');
    expect(nodeActive(container, 'template')).toBe('true');
    expect(screen.getByText(/Auto-fix is off/)).toBeInTheDocument();
  });

  it('create mode shows that nothing runs on the Test instance', () => {
    const { container } = renderDiagram({ autoFix: true, autoPromote: true, maxRetries: 2, testHealthy: true });
    fireEvent.click(screen.getByRole('tab', { name: 'Create Workflow' }));
    expect(screen.getByText(/runs step 1 only/)).toBeInTheDocument();
    expect(nodeActive(container, 'test')).toBe('false');
    expect(container.querySelector('[data-node="generate"]')?.getAttribute('data-current')).toBe('true');
    expect(container.querySelector('[data-end="create"]')).toHaveTextContent('End · no test');
    expect(screen.queryByRole('switch')).toBeNull();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('highlights the first step before anything has played', () => {
    renderDiagram({ autoFix: true, autoPromote: true, maxRetries: 2, testHealthy: true });
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
      renderDiagram({ autoFix: true, autoPromote: true, maxRetries: 2, testHealthy: true });
      fireEvent.click(screen.getByRole('button', { name: 'Play flow' }));
      expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument();
    } finally {
      window.matchMedia = original;
    }
  });

  it('switches in the card drive the same state as the form', () => {
    const onAutoFixChange = vi.fn();
    const onAutoPromoteChange = vi.fn();
    renderDiagram({ onAutoFixChange, onAutoPromoteChange, autoPromote: true });
    const [fix, promote] = screen.getAllByRole('switch');
    fireEvent.click(fix!);
    fireEvent.click(promote!);
    expect(onAutoFixChange).toHaveBeenCalledWith(true);
    expect(onAutoPromoteChange).toHaveBeenCalledWith(false);
    expect(screen.getByText('only with auto-fix')).toBeInTheDocument();
  });

  it('warns when the Test instance is offline', () => {
    renderDiagram({ autoFix: false, autoPromote: false, maxRetries: 2, testHealthy: false });
    expect(screen.getByText(/offline right now/)).toBeInTheDocument();
  });

  it('remembers the collapsed state', () => {
    const { unmount } = renderDiagram({ autoFix: false, autoPromote: false, maxRetries: 2, testHealthy: true });
    fireEvent.click(screen.getByRole('button', { name: /How the factory works/ }));
    expect(screen.queryByRole('tablist')).toBeNull();
    unmount();
    renderDiagram({ autoFix: false, autoPromote: false, maxRetries: 2, testHealthy: true });
    expect(screen.queryByRole('tablist')).toBeNull();
  });
});
