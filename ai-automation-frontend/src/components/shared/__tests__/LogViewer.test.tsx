import { describe, it, expect, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { LogViewer, type LogEntry } from '../LogViewer';
import { useI18nStore } from '../../../i18n';

function setScrollGeometry(el: HTMLElement, scrollHeight: number, clientHeight: number) {
  Object.defineProperty(el, 'scrollHeight', { configurable: true, get: () => scrollHeight });
  Object.defineProperty(el, 'clientHeight', { configurable: true, get: () => clientHeight });
}

const event = (id: string, message: string): LogEntry =>
  ({ id, timestamp: '2026-09-14T10:00:00Z', level: 'INFO', message });

describe('LogViewer', () => {
  beforeEach(() => {
    useI18nStore.setState({ locale: 'en' });
  });

  it('shows the translated placeholder without logs', () => {
    const { rerender } = render(<LogViewer logs={[]} />);
    expect(screen.getByText('Waiting for logs...')).toBeInTheDocument();

    useI18nStore.setState({ locale: 'de' });
    rerender(<LogViewer logs={[]} />);
    expect(screen.getByText('Warte auf Logs...')).toBeInTheDocument();
  });

  it('keeps row DOM nodes stable when lines are appended and old ones dropped', () => {
    const { rerender } = render(<LogViewer logs={[event('a', 'first'), event('b', 'second')]} />);
    const secondRow = screen.getByText('second').closest('[data-kind]');

    rerender(<LogViewer logs={[event('b', 'second'), event('c', 'third')]} />);

    expect(screen.getByText('second').closest('[data-kind]')).toBe(secondRow);
    expect(screen.queryByText('first')).not.toBeInTheDocument();
  });

  it('styles live lines by kind and labels a step once per run of lines', () => {
    render(<LogViewer logs={[
      { id: '1', message: 'Bash: mvn test', kind: 'tool', stepLabel: 'Step 1 · Build', level: 'INFO' },
      { id: '2', message: '↳ 2.0 KB', kind: 'tool_result', stepLabel: 'Step 1 · Build', level: 'INFO' },
      { id: '3', message: '↳ error · 3 B', kind: 'tool_error', stepLabel: 'Step 2', level: 'ERROR' },
    ]} />);

    expect(screen.getByText('Bash: mvn test').closest('[data-kind]')).toHaveAttribute('data-kind', 'tool');
    expect(screen.getAllByText('Step 1 · Build')).toHaveLength(1);
    expect(screen.getByText('Step 2')).toBeInTheDocument();
    // live lines show an icon instead of the level text
    expect(screen.queryByText('ERROR')).not.toBeInTheDocument();
  });

  it('renders the skipped marker with the translated count', () => {
    render(<LogViewer logs={[{ id: 's', message: '… 4 lines skipped', level: 'WARN', skipped: 4 }]} />);

    expect(screen.getByText('… 4 lines skipped').closest('[data-kind]')).toHaveAttribute('data-kind', 'skipped');
  });

  it('follows new lines only while scrolled to the bottom, and offers a jump button otherwise', () => {
    const { rerender } = render(<LogViewer logs={[event('a', 'one')]} />);
    const container = screen.getByTestId('log-viewer');
    setScrollGeometry(container, 1000, 200);

    rerender(<LogViewer logs={[event('a', 'one'), event('b', 'two')]} />);
    expect(container.scrollTop).toBe(1000); // following: stuck to the bottom

    container.scrollTop = 100;
    fireEvent.scroll(container);
    rerender(<LogViewer logs={[event('a', 'one'), event('b', 'two'), event('c', 'three')]} />);
    expect(container.scrollTop).toBe(100); // reading older lines: not interrupted

    fireEvent.click(screen.getByRole('button', { name: 'Jump to latest' }));
    expect(container.scrollTop).toBe(1000);
    expect(screen.queryByRole('button', { name: 'Jump to latest' })).not.toBeInTheDocument();
  });
});
