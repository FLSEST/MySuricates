import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { CronPicker } from '../CronPicker';
import { getTranslations } from '../../../i18n';

vi.mock('../../../hooks/useSchedules', () => ({
  useCronPreview: () => ({ data: undefined }),
}));

const t = getTranslations();

function cronPreview(): string | null {
  return document.querySelector('code')?.textContent ?? null;
}

function timeInput(): HTMLInputElement {
  return document.querySelector('input[type="time"]') as HTMLInputElement;
}

function rawInput(): HTMLInputElement {
  return screen.getByPlaceholderText('0 0 0 * * *') as HTMLInputElement;
}

describe('CronPicker', () => {
  it('parses a daily expression into the time picker and cron preview', () => {
    render(<CronPicker value="0 30 9 * * *" onChange={vi.fn()} />);
    expect(timeInput().value).toBe('09:30');
    expect(cronPreview()).toBe('0 30 9 * * *');
  });

  it('follows a changed external value', () => {
    const { rerender } = render(<CronPicker value="0 30 9 * * *" onChange={vi.fn()} />);
    rerender(<CronPicker value="0 0 8 * * 1,3" onChange={vi.fn()} />);
    expect(timeInput().value).toBe('08:00');
    expect(cronPreview()).toBe('0 0 8 * * 1,3');
  });

  it('keeps local edits while the external value is unchanged', () => {
    const onChange = vi.fn();
    const { rerender } = render(<CronPicker value="0 30 9 * * *" onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: t.schedules.monthly }));
    expect(onChange).toHaveBeenCalledWith('0 30 9 1 * *');
    expect(cronPreview()).toBe('0 30 9 1 * *');

    // parent re-renders without adopting the change: the local state must survive
    rerender(<CronPicker value="0 30 9 * * *" onChange={onChange} />);
    expect(cronPreview()).toBe('0 30 9 1 * *');
  });

  it('shows and syncs the raw expression in advanced mode', () => {
    const { rerender } = render(<CronPicker value="0 0 12 1 6 *" onChange={vi.fn()} />);
    expect(rawInput().value).toBe('0 0 12 1 6 *');
    rerender(<CronPicker value="0 15 10 15 12 *" onChange={vi.fn()} />);
    expect(rawInput().value).toBe('0 15 10 15 12 *');
  });
});
