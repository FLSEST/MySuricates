import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { CronPicker } from '../CronPicker';
import { useI18nStore } from '../../../i18n';
import { formatTimestamp } from '../../../utils/localeFormat';

const preview = vi.hoisted(() => ({
  data: undefined as undefined | { runs: string[]; pausedUntil?: string },
}));

vi.mock('../../../hooks/useSchedules', () => ({
  useCronPreview: () => preview,
}));

beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
});

/** The preview of the next runs says that they start after an active skip-until pause (a776fd2a). */
describe('CronPicker next-runs preview', () => {
  it('names the pause when the runs start after it', () => {
    preview.data = { runs: ['2026-10-02T06:00:00Z'], pausedUntil: '2026-10-01T22:00:00Z' };

    render(<CronPicker value="0 0 8 * * *" onChange={vi.fn()} />);

    expect(screen.getByText(/Next runs after the pause/)).toHaveTextContent(
      `Next runs after the pause (until ${formatTimestamp('2026-10-01T22:00:00Z', 'en')})`);
  });

  it('keeps the plain label without a pause', () => {
    preview.data = { runs: ['2026-10-02T06:00:00Z'] };

    render(<CronPicker value="0 0 8 * * *" onChange={vi.fn()} />);

    expect(screen.getByText(/Next runs/)).not.toHaveTextContent('after the pause');
  });
});
