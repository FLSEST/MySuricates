/**
 * `SystemInfo` grows where the backoff, the runtime mode or the next field is needed
 * - not here. The card lists the fields generically, and exactly that crashed the whole
 * settings page when `rateLimit` was added as a nested object on 2026-09-18
 * (0d1d27a1): "Objects are not valid as a React child". Type-correct, unnoticed for three days, caught by
 * no test. These tests catch the next case.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { SystemInfoCard } from '../SystemInfoCard';
import { useListenAddresses, useSystemHealth, useSystemInfo } from '../../../hooks/useSystem';
import { useI18nStore } from '../../../i18n';

vi.mock('../../../hooks/useSystem', () => ({
  useSystemHealth: vi.fn(),
  useSystemInfo: vi.fn(),
  useListenAddresses: vi.fn(),
}));

const info = {
  name: 'MySuricates',
  version: '0.7.5',
  java: '21.0.12',
  environment: 'Dev',
  rateLimit: { enabled: true, requestsPerMinute: 120, globalBackoffSeconds: 2, defaultRetryAfterSeconds: 60 },
};

beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  vi.mocked(useSystemHealth).mockReturnValue({ data: undefined } as never);
  vi.mocked(useSystemInfo).mockReturnValue({ data: info } as never);
  vi.mocked(useListenAddresses).mockReturnValue({ data: undefined } as never);
});

describe('SystemInfoCard', () => {
  it('renders without crashing when a field is a nested object', () => {
    expect(() => render(<SystemInfoCard />)).not.toThrow();
  });

  it('shows the primitive fields', () => {
    render(<SystemInfoCard />);
    expect(screen.getByText('0.7.5')).toBeInTheDocument();
    expect(screen.getByText('21.0.12')).toBeInTheDocument();
    expect(screen.getByText('Dev')).toBeInTheDocument();
  });

  it('leaves the nested field out instead of stringifying it', () => {
    render(<SystemInfoCard />);
    expect(screen.queryByText('rateLimit')).not.toBeInTheDocument();
    expect(screen.queryByText(/\[object Object\]/)).not.toBeInTheDocument();
    expect(screen.queryByText(/requestsPerMinute/)).not.toBeInTheDocument();
  });

  it('renders booleans and numbers as text', () => {
    vi.mocked(useSystemInfo).mockReturnValue({ data: { ...info, tailscale: false, uptimeDays: 0 } } as never);
    render(<SystemInfoCard />);
    expect(screen.getByText('false')).toBeInTheDocument();
    expect(screen.getByText('0')).toBeInTheDocument();
  });

  // 6bb7ed36: the card showed the raw API keys and "Last Check" in every language
  it('labels the fields in the UI language and keeps the key of one it does not know', () => {
    vi.mocked(useSystemInfo).mockReturnValue({ data: { ...info, runtimeMode: 'jar', nextField: 'x' } } as never);
    vi.mocked(useSystemHealth).mockReturnValue({
      data: { status: 'UP', docker: 'CONNECTED', timestamp: '2026-09-21T12:00:00Z' },
    } as never);
    const { rerender } = render(<SystemInfoCard />);
    expect(screen.getByText('Environment')).toBeInTheDocument();
    expect(screen.getByText('Runtime mode')).toBeInTheDocument();
    expect(screen.getByText('Last check')).toBeInTheDocument();

    act(() => useI18nStore.setState({ locale: 'de' }));
    rerender(<SystemInfoCard />);
    expect(screen.getByText('Umgebung')).toBeInTheDocument();
    expect(screen.getByText('Laufzeitmodus')).toBeInTheDocument();
    expect(screen.getByText('Letzte Prüfung')).toBeInTheDocument();
    expect(screen.queryByText('environment')).not.toBeInTheDocument();
    expect(screen.getByText('nextField')).toHaveClass('capitalize');
  });

  it('survives a null field', () => {
    vi.mocked(useSystemInfo).mockReturnValue({ data: { ...info, tailscale: null } } as never);
    expect(() => render(<SystemInfoCard />)).not.toThrow();
  });

  it('says how the instance runs, from the keyed listen report (1af11dcc)', () => {
    vi.mocked(useListenAddresses).mockReturnValue({ data: { runtime: 'container' } } as never);
    const { rerender } = render(<SystemInfoCard />);
    expect(screen.getByText('Runs')).toBeInTheDocument();
    expect(screen.getByText('in a container')).toBeInTheDocument();

    vi.mocked(useListenAddresses).mockReturnValue({ data: { runtime: 'host' } } as never);
    act(() => useI18nStore.setState({ locale: 'de' }));
    rerender(<SystemInfoCard />);
    expect(screen.getByText('Läuft')).toBeInTheDocument();
    expect(screen.getByText('direkt auf dem Rechner')).toBeInTheDocument();
  });
});
