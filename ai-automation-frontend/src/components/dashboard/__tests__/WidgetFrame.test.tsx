import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import { reportErrorToBackend } from '../../../utils/errorReporter';
import { WidgetFrame } from '../WidgetFrame';

vi.mock('../../../utils/errorReporter', () => ({ reportErrorToBackend: vi.fn() }));

let shouldThrow = true;
function Flaky() {
  if (shouldThrow) throw new Error('boom');
  return <p>Widget content</p>;
}

function ThrowsNull() {
  throw null;
}

beforeAll(() => {
  // React logs caught render errors; keep the test output readable.
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  shouldThrow = true;
  vi.mocked(reportErrorToBackend).mockClear();
});

describe('WidgetFrame', () => {
  it('contains a crashing widget and keeps its neighbours rendered', () => {
    render(
      <>
        <WidgetFrame label="Runs Over Time"><Flaky /></WidgetFrame>
        <p>Neighbour</p>
      </>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Runs Over Time');
    expect(screen.getByText('This widget could not be displayed.')).toBeInTheDocument();
    expect(screen.getByText('Neighbour')).toBeInTheDocument();
  });

  it('renders the widget again after retry', () => {
    render(<WidgetFrame label="Runs Over Time"><Flaky /></WidgetFrame>);
    shouldThrow = false;
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(screen.getByText('Widget content')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('reports the error to the backend', () => {
    render(<WidgetFrame label="Runs Over Time"><Flaky /></WidgetFrame>);
    expect(vi.mocked(reportErrorToBackend).mock.calls[0]?.[0]).toMatchObject({ message: 'boom' });
  });

  it('renders children untouched when nothing fails', () => {
    shouldThrow = false;
    const { container } = render(<WidgetFrame label="X"><Flaky /></WidgetFrame>);
    expect(container.innerHTML).toBe('<p>Widget content</p>');
  });

  it('contains a widget that throws a non-Error value', () => {
    render(
      <>
        <WidgetFrame label="Runs Over Time"><ThrowsNull /></WidgetFrame>
        <p>Neighbour</p>
      </>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Runs Over Time');
    expect(screen.getByText('Neighbour')).toBeInTheDocument();
  });
});
