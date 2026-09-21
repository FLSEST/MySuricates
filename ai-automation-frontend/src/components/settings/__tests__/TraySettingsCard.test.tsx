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
    render(<TraySettingsCard />);

    fireEvent.click(screen.getByRole('switch', { name: /desktop popups/i }));

    expect(screen.getByRole('switch', { name: /waiting approvals/i })).toBeDisabled();
    expect(screen.getByRole('switch', { name: /failed workflows/i })).toBeDisabled();
    expect(screen.getByRole('switch', { name: /completed workflows/i })).toBeDisabled();
  });

  it('saves popup choices and the browser target together', () => {
    render(<TraySettingsCard />);

    fireEvent.click(screen.getByRole('switch', { name: /completed workflows/i }));
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }));

    expect(mutate).toHaveBeenCalledWith({ ...settings, notifySuccess: true });
  });

  it('can copy the currently opened address into the browser target', () => {
    render(<TraySettingsCard />);

    fireEvent.click(screen.getByRole('button', { name: /use current address/i }));

    expect(screen.getByDisplayValue(window.location.origin)).toBeInTheDocument();
  });
});
