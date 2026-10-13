import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ListenAddressesCard } from '../ListenAddressesCard';
import { useListenAddresses } from '../../../hooks/useSystem';
import { useI18nStore } from '../../../i18n';
import type { ListenReport } from '../../../types';

vi.mock('../../../hooks/useSystem', () => ({ useListenAddresses: vi.fn() }));

function reports(report: ListenReport) {
  vi.mocked(useListenAddresses).mockReturnValue({ data: report, isError: false } as never);
}

const devHost: ListenReport = {
  runtime: 'host',
  backend: { scheme: 'http', address: '127.0.0.1', port: 9090, scope: 'LOCAL', published: false },
  servesFrontend: false,
  bindSwitch: 'MYSURICATES_DEV_BIND',
  switchSource: 'scripts/start-mysuricates-dev.sh',
};

beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
});

describe('ListenAddressesCard', () => {
  it('shows the dev backend on loopback, Vite as the frontend and the switch', () => {
    reports(devHost);

    render(<ListenAddressesCard />);

    expect(screen.getByText('http://127.0.0.1:9090')).toBeInTheDocument();
    expect(screen.getByText('To change: MYSURICATES_DEV_BIND=… (scripts/start-mysuricates-dev.sh), then restart.'))
      .toBeInTheDocument();
    // __DEV_BIND__ is 127.0.0.1 in the test build (vitest.config.ts); scheme and port come from the page
    const protocol = window.location.protocol.replace(':', '');
    const port = window.location.port ? `:${window.location.port}` : '';
    expect(screen.getByText(`${protocol}://127.0.0.1${port}`)).toBeInTheDocument();
    expect(screen.getByText(/Served by Vite/)).toBeInTheDocument();
    expect(screen.getAllByText('this machine only').length).toBeGreaterThanOrEqual(2);
  });

  it('names the address the page was opened under, localhost as this machine', () => {
    reports(devHost);

    render(<ListenAddressesCard />);

    expect(screen.getByText('Connected via')).toBeInTheDocument();
    expect(screen.getByText(window.location.origin)).toBeInTheDocument();
  });

  it('shows the published network address of a container that serves the UI itself', () => {
    reports({
      runtime: 'container',
      backend: { scheme: 'http', address: null, port: 8090, scope: 'NETWORK', published: true },
      servesFrontend: true,
      bindSwitch: 'TEST_BIND',
      switchSource: '/home/user/.config/mysuricates/staging.env',
    });

    render(<ListenAddressesCard />);

    expect(screen.getAllByText('http://0.0.0.0:8090')).toHaveLength(2);
    expect(screen.getAllByText('reachable on the network')).toHaveLength(2);
    expect(screen.getByText(/where Docker publishes the port/)).toBeInTheDocument();
    expect(screen.getByText('To change: TEST_BIND=… (/home/user/.config/mysuricates/staging.env), then restart.'))
      .toBeInTheDocument();
    expect(screen.getByText('Served by the backend – the same address.')).toBeInTheDocument();
    expect(screen.queryByText(/Served by Vite/)).not.toBeInTheDocument();
  });

  it('says unknown instead of guessing for a container from an older script', () => {
    reports({
      runtime: 'container',
      backend: { scheme: 'http', address: null, port: null, scope: 'UNKNOWN', published: true },
      servesFrontend: true,
      bindSwitch: null,
      switchSource: null,
    });

    render(<ListenAddressesCard />);

    expect(screen.getAllByText('unknown').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText(/older start script/)).toBeInTheDocument();
    expect(screen.queryByText(/To change/)).not.toBeInTheDocument();
  });

  it('calls the tryout fixed', () => {
    reports({
      runtime: 'tryout',
      backend: { scheme: 'http', address: '127.0.0.1', port: 9095, scope: 'LOCAL', published: true },
      servesFrontend: true,
      bindSwitch: null,
      switchSource: null,
    });

    render(<ListenAddressesCard />);

    expect(screen.getByText('Fixed in the tryout.')).toBeInTheDocument();
  });

  it('speaks German', () => {
    useI18nStore.setState({ locale: 'de' });
    reports(devHost);

    render(<ListenAddressesCard />);

    expect(screen.getByText('Erreichbarkeit')).toBeInTheDocument();
    expect(screen.getAllByText('nur dieser Rechner').length).toBeGreaterThanOrEqual(2);
  });
});
