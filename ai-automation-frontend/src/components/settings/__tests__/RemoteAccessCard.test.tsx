/**
 * The `remote-access` module shows a container hint instead of the enable button when the backend
 * reports `inContainer: true` (no supported Tailscale path in a container). The frontend goes live
 * with the merge before the backend restarts, so the card must not break on the OLD response shape
 * that has no `inContainer` field at all — it must fall back to the normal host controls.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RemoteAccessCard } from '../RemoteAccessCard';
import { useRemoteAccessStatus, useEnableRemoteAccess, useDisableRemoteAccess } from '../../../hooks/useSystem';
import { useI18nStore } from '../../../i18n';

vi.mock('../../../hooks/useSystem', () => ({
  useRemoteAccessStatus: vi.fn(),
  useEnableRemoteAccess: vi.fn(),
  useDisableRemoteAccess: vi.fn(),
}));

beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  vi.mocked(useEnableRemoteAccess).mockReturnValue({ mutate: vi.fn(), isPending: false } as never);
  vi.mocked(useDisableRemoteAccess).mockReturnValue({ mutate: vi.fn(), isPending: false } as never);
});

describe('RemoteAccessCard', () => {
  it('shows the enable button on the OLD response shape (no inContainer field)', () => {
    vi.mocked(useRemoteAccessStatus).mockReturnValue({
      data: { enabled: false, ip: null, hostname: null },
    } as never);

    render(<RemoteAccessCard />);

    expect(screen.getByText('Enable Remote Access')).toBeInTheDocument();
    expect(screen.queryByText(/runs in a container/)).not.toBeInTheDocument();
  });

  it('shows the enable button on the NEW response shape when inContainer is false', () => {
    vi.mocked(useRemoteAccessStatus).mockReturnValue({
      data: { enabled: false, ip: null, hostname: null, inContainer: false },
    } as never);

    render(<RemoteAccessCard />);

    expect(screen.getByText('Enable Remote Access')).toBeInTheDocument();
  });

  it('shows the disable button when enabled on the host (no inContainer field)', () => {
    vi.mocked(useRemoteAccessStatus).mockReturnValue({
      data: { enabled: true, ip: '100.64.1.2', hostname: 'my-host' },
    } as never);

    render(<RemoteAccessCard />);

    expect(screen.getByText('Disable Remote Access')).toBeInTheDocument();
  });

  it('shows the container hint instead of a button when inContainer is true', () => {
    vi.mocked(useRemoteAccessStatus).mockReturnValue({
      data: { enabled: false, ip: null, hostname: null, inContainer: true },
    } as never);

    render(<RemoteAccessCard />);

    expect(screen.queryByText('Enable Remote Access')).not.toBeInTheDocument();
    expect(screen.queryByText('Disable Remote Access')).not.toBeInTheDocument();
    expect(screen.getByText(/runs in a container/)).toBeInTheDocument();
  });

  it('translates the container hint into German', () => {
    useI18nStore.setState({ locale: 'de' });
    vi.mocked(useRemoteAccessStatus).mockReturnValue({
      data: { enabled: false, ip: null, hostname: null, inContainer: true },
    } as never);

    render(<RemoteAccessCard />);

    expect(screen.getByText(/läuft in einem Container/)).toBeInTheDocument();
  });
});
