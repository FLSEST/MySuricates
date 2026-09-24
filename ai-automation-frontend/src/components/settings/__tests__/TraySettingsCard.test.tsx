import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { TraySettingsCard } from '../TraySettingsCard';
import { useTraySettings, useUpdateTraySettings } from '../../../hooks/useTraySettings';

vi.mock('../../../hooks/useTraySettings', () => ({
  useTraySettings: vi.fn(),
  useUpdateTraySettings: vi.fn(),
}));

const mutate = vi.fn();
const settings = {
  popupsEnabled: true,
  notifyApprovals: true,
  notifyFailures: true,
  notifySuccess: false,
  publicUrl: 'https://mysuricates.example.com',
};

beforeEach(() => {
  mutate.mockReset();
  vi.mocked(useTraySettings).mockReturnValue({ data: settings, isLoading: false } as never);
  vi.mocked(useUpdateTraySettings).mockReturnValue({ mutate, isPending: false } as never);
});

describe('TraySettingsCard', () => {
  it('disables event choices when the popup master switch is off', () => {
    vi.mocked(useTraySettings).mockReturnValue({
      data: { ...settings, popupsEnabled: false },
      isLoading: false,
    } as never);
    render(<TraySettingsCard />);

    expect(screen.getByRole('switch', { name: /waiting approvals/i })).toBeDisabled();
    expect(screen.getByRole('switch', { name: /failed workflows/i })).toBeDisabled();
    expect(screen.getByRole('switch', { name: /completed workflows/i })).toBeDisabled();
  });

  it('saves popup choices immediately as partial updates', () => {
    render(<TraySettingsCard />);

    fireEvent.click(screen.getByRole('switch', { name: /completed workflows/i }));

    expect(mutate).toHaveBeenCalledWith({ notifySuccess: true });
  });

  it('saves the browser target separately', () => {
    render(<TraySettingsCard />);

    fireEvent.click(screen.getByRole('button', { name: /use current address/i }));
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }));

    expect(screen.getByDisplayValue(window.location.origin)).toBeInTheDocument();
    expect(mutate).toHaveBeenCalledWith(
      { publicUrl: window.location.origin },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    );
  });

  it('follows the stored URL until the user edits it, then keeps the draft', () => {
    const { rerender } = render(<TraySettingsCard />);
    expect(screen.getByDisplayValue(settings.publicUrl)).toBeInTheDocument();

    vi.mocked(useTraySettings).mockReturnValue({
      data: { ...settings, publicUrl: 'https://other.example.com' },
      isLoading: false,
    } as never);
    rerender(<TraySettingsCard />);
    const field = screen.getByDisplayValue('https://other.example.com');

    fireEvent.change(field, { target: { value: 'https://draft.example.com' } });
    vi.mocked(useTraySettings).mockReturnValue({
      data: { ...settings, publicUrl: 'https://third.example.com' },
      isLoading: false,
    } as never);
    rerender(<TraySettingsCard />);
    expect(screen.getByDisplayValue('https://draft.example.com')).toBeInTheDocument();
  });
});
