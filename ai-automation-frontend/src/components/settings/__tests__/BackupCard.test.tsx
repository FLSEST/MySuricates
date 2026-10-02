import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { BackupCard } from '../BackupCard';
import { useUpdateSystemSetting } from '../../../hooks/useSystem';
import { api } from '../../../api/client';

vi.mock('../../../hooks/useSystem', () => ({
  useBackupList: () => ({ data: [] }),
  useUpdateSystemSetting: vi.fn(),
}));

vi.mock('../../../api/client', () => ({
  api: { get: vi.fn() },
}));

const mutate = vi.fn();

beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  mutate.mockReset();
  vi.mocked(api.get).mockResolvedValue({ value: '14' });
  vi.mocked(useUpdateSystemSetting).mockReturnValue({ mutate, isPending: false } as never);
});

describe('BackupCard retention count', () => {
  it('shows the stored value and saves the edited one', async () => {
    renderWithProviders(<BackupCard />);
    const field = await screen.findByDisplayValue('14');

    fireEvent.change(field, { target: { value: '21' } });
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }));

    expect(mutate).toHaveBeenCalledWith(
      { key: 'backup-retain-count', value: '21' },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    );
  });

  it('keeps the saved value in the field once the save succeeded', async () => {
    mutate.mockImplementation((_vars, options: { onSuccess: () => void }) => options.onSuccess());
    renderWithProviders(<BackupCard />);
    const field = await screen.findByDisplayValue('14');

    fireEvent.change(field, { target: { value: '21' } });
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }));

    expect(screen.getByDisplayValue('21')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^save$/i })).not.toBeInTheDocument();
  });

  /** 8f1343d1: without a stored value the configured default applies, not a number of the UI's own. */
  it('shows the configured default the backend names when nothing is stored', async () => {
    vi.mocked(api.get).mockResolvedValue({ key: 'backup-retain-count', value: '', default: '45' });
    renderWithProviders(<BackupCard />);

    expect(await screen.findByDisplayValue('45')).toBeInTheDocument();
  });

  it('prefers the stored value over the default', async () => {
    vi.mocked(api.get).mockResolvedValue({ key: 'backup-retain-count', value: '7', default: '45' });
    renderWithProviders(<BackupCard />);

    expect(await screen.findByDisplayValue('7')).toBeInTheDocument();
  });

  it('falls back to 30 against a backend without the default field', async () => {
    vi.mocked(api.get).mockResolvedValue({ key: 'backup-retain-count', value: '' });
    renderWithProviders(<BackupCard />);

    expect(await screen.findByDisplayValue('30')).toBeInTheDocument();
    expect(api.get).toHaveBeenCalledWith('/system/settings/backup-retain-count');
  });
});
