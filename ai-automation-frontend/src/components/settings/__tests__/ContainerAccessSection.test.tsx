import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import type { ContainerAccess } from '../../../hooks/useSystemCapabilities';
import { ContainerAccessSection } from '../ContainerAccessSection';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const STABLE: ContainerAccess = {
  runtime: 'container',
  aiFromHost: 'login',
  dockerSocket: true,
  switchSource: '/home/u/.config/mysuricates/staging.env',
  switchPrefix: 'STABLE_',
  providers: [
    { id: 'claude', selected: true, program: true, login: false },
    { id: 'codex', selected: true, program: true, login: true },
    { id: 'pi', selected: false, program: true, login: true },
  ],
};

function serve(containerAccess: ContainerAccess | undefined) {
  server.use(
    http.get('/api/v1/system/capabilities', () =>
      HttpResponse.json({ ...modulesFixture('full'), ...(containerAccess ? { containerAccess } : {}) })),
  );
}

describe('ContainerAccessSection', () => {
  it('shows what came from the host, per provider, and the lines to change it — read-only', async () => {
    serve(STABLE);
    renderWithProviders(<ContainerAccessSection />);

    const section = await screen.findByTestId('container-access');
    expect(within(section).getByTestId('container-access-runtime')).toHaveTextContent('in a container');
    expect(within(section).getByTestId('container-access-mode')).toHaveTextContent('just the login of each provider');
    expect(within(section).getByTestId('container-access-socket')).toHaveTextContent('On');
    expect(within(section).getByTestId('container-access-provider-claude')).toHaveTextContent('Claude');
    expect(within(section).getByTestId('container-access-provider-claude')).toHaveTextContent('no login');
    expect(within(section).getByTestId('container-access-provider-codex')).toHaveTextContent('ready');
    expect(within(section).getByTestId('container-access-provider-pi')).toHaveTextContent('not selected');

    // The socket defeats the boundary: said out loud
    expect(within(section).getByTestId('container-access-socket-risk')).toHaveTextContent(/whole machine/);
    expect(within(section).queryByTestId('container-access-all-risk')).not.toBeInTheDocument();

    // Where to change it, with the names of that path and the values this instance runs with
    // The place stays in its own line, in its real case — the snippet label is set in capitals
    expect(within(section).getByTestId('container-access-change-in'))
      .toHaveTextContent('Change in /home/u/.config/mysuricates/staging.env, then restart:');
    expect(within(section).getByText('/home/u/.config/mysuricates/staging.env').tagName).toBe('CODE');
    const snippet = within(section).getByText(/STABLE_AI_FROM_HOST=login/);
    expect(snippet).toHaveTextContent('STABLE_AI_PROVIDERS=claude,codex');
    expect(snippet).toHaveTextContent('STABLE_DOCKER_SOCKET=1');

    // Read-only: the copy button is the only control
    const buttons = within(section).getAllByRole('button');
    expect(buttons).toHaveLength(1);
    expect(buttons[0]).toHaveAccessibleName(/copy/i);
  });

  it('warns when the whole provider directories come from the host', async () => {
    serve({ ...STABLE, aiFromHost: 'all', dockerSocket: false });
    renderWithProviders(<ContainerAccessSection />);

    const section = await screen.findByTestId('container-access');
    expect(within(section).getByTestId('container-access-all-risk')).toHaveTextContent(/hooks, plugins, memory/);
    expect(within(section).queryByTestId('container-access-socket-risk')).not.toBeInTheDocument();
    expect(within(section).getByText(/STABLE_DOCKER_SOCKET=0/)).toBeInTheDocument();
  });

  it('on the host: no switches, a plain note instead of a boundary', async () => {
    serve({
      runtime: 'host', aiFromHost: null, dockerSocket: true, switchSource: null, switchPrefix: null,
      providers: [
        { id: 'claude', selected: true, program: true, login: null },
        { id: 'codex', selected: true, program: false, login: null },
        { id: 'pi', selected: true, program: true, login: null },
      ],
    });
    renderWithProviders(<ContainerAccessSection />);

    const section = await screen.findByTestId('container-access');
    expect(within(section).getByTestId('container-access-runtime')).toHaveTextContent('directly on this machine');
    expect(within(section).getByTestId('container-access-host-note')).toHaveTextContent(/whole home directory/);
    expect(within(section).queryByTestId('container-access-mode')).not.toBeInTheDocument();
    expect(within(section).queryByTestId('container-access-socket-risk')).not.toBeInTheDocument();
    expect(within(section).getByTestId('container-access-provider-claude')).toHaveTextContent('program found');
    expect(within(section).getByTestId('container-access-provider-codex')).toHaveTextContent('program missing');
    expect(within(section).queryAllByRole('button')).toHaveLength(0);
  });

  it('renders nothing for an older backend without the report', async () => {
    serve(undefined);
    const { container } = renderWithProviders(<ContainerAccessSection />);
    await new Promise((r) => setTimeout(r, 50));
    expect(container.querySelector('[data-testid="container-access"]')).toBeNull();
  });
});
