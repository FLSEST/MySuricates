import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { CatchUpQueueBanner } from '../CatchUpQueueBanner';

describe('CatchUpQueueBanner', () => {
  it('renders nothing while the queue is empty', () => {
    const { container } = render(<CatchUpQueueBanner queue={{ active: null, pending: [] }} />);
    expect(container).toBeEmptyDOMElement();
    const { container: undef } = render(<CatchUpQueueBanner queue={undefined} />);
    expect(undef).toBeEmptyDOMElement();
  });

  it('shows the running catch-up and the waiting schedules in order', () => {
    render(
      <CatchUpQueueBanner
        queue={{
          active: { scheduleId: 's1', targetName: 'Nightly QA Monitor', runId: 'r1', startedAt: '2026-09-14T08:00:00Z' },
          pending: [
            { scheduleId: 's2', targetName: 'Backend Quality Gate', missedAt: '2026-09-14T04:30:00Z' },
            { scheduleId: 's3', targetName: 'Lebens-Radar', missedAt: '2026-09-14T05:30:00Z' },
          ],
        }}
      />,
    );
    const banner = screen.getByTestId('catch-up-queue-banner');
    expect(banner).toHaveTextContent('Nightly QA Monitor');
    expect(banner).toHaveTextContent('(2)');
    expect(banner).toHaveTextContent('Backend Quality Gate → Lebens-Radar');
  });
});
