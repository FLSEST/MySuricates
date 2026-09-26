import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor, fireEvent, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { Integrations } from '../Integrations';
import type { IntegrationProviderInfo } from '../../types';

const BASE = '/api/v1';

// A connected GitHub provider mirroring the backend DTO shape
// (accountEmail is null for GitHub in reality; accountName carries the login).
const connectedGithub: IntegrationProviderInfo = {
  providerId: 'github',
  displayName: 'GitHub',
  icon: 'Github',
  description: 'Sync GitHub issues',
  status: 'CONNECTED',
  configured: true,
  requiresCredentials: true,
  accountEmail: null,
  accountName: 'octocat',
  connectedSince: '2026-06-12T09:30:00Z',
  scopes: 'repo read:user',
  optionalScopes: [
    { scope: 'workflow', label: 'Workflow access', granted: false },
  ],
  actions: [
    { name: 'list_repos', description: 'List repositories', inputSchema: {}, available: true },
    { name: 'create_issue', description: 'Create an issue', inputSchema: {}, available: true },
  ],
  supportsTaskExport: true,
};

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function useProviders(providers: IntegrationProviderInfo[]) {
  server.use(http.get(`${BASE}/integrations`, () => HttpResponse.json(providers)));
}

async function selectGithub() {
  renderWithProviders(<Integrations />);
  const card = await screen.findByText('GitHub');
  fireEvent.click(card);
}

describe('Integrations — connected state', () => {
  it('shows connection details (account, connected-since, granted permissions) for a connected provider', async () => {
    useProviders([connectedGithub]);
    await selectGithub();

    await waitFor(() => {
      expect(screen.getByText('Connection details')).toBeInTheDocument();
    });
    // Account label + login
    expect(screen.getByText('Account')).toBeInTheDocument();
    expect(screen.getByText('octocat')).toBeInTheDocument();
    // Connected-since label (value is locale-formatted, so we only assert the label)
    expect(screen.getByText('Connected since')).toBeInTheDocument();
    // Granted permissions rendered as chips
    expect(screen.getByText('Granted permissions')).toBeInTheDocument();
    expect(screen.getByText('read:user')).toBeInTheDocument();
  });

  it('still renders the Available Actions list for a connected provider', async () => {
    useProviders([connectedGithub]);
    await selectGithub();

    await waitFor(() => {
      expect(screen.getByText(/Available Actions/)).toBeInTheDocument();
    });
    expect(screen.getByText('list_repos')).toBeInTheDocument();
    expect(screen.getByText('create_issue')).toBeInTheDocument();
  });

  it('exposes the collapsible configuration / setup guide with a credentials-update control', async () => {
    useProviders([connectedGithub]);
    await selectGithub();

    await waitFor(() => {
      expect(screen.getByText('Configuration & setup guide')).toBeInTheDocument();
    });
    // The connected SetupGuidePanel offers "Update credentials" (heading + button)
    expect(screen.getAllByText('Update credentials').length).toBeGreaterThanOrEqual(1);
    // ...and shows the "credentials are saved" hint instead of the first-time wording
    expect(
      screen.getByText(/Credentials are saved/i),
    ).toBeInTheDocument();
  });
});

describe('Integrations — verification notice', () => {
  // A made-up provider id: shipped ids get a localized display name from i18n.
  const simulatedProvider: IntegrationProviderInfo = {
    ...connectedGithub,
    providerId: 'example-forge',
    displayName: 'Example Forge',
    status: 'DISCONNECTED',
    configured: true,
    accountName: null,
    connectedSince: null,
    scopes: null,
    verification: 'SIMULATED',
    verifiedAgainst: null,
  };

  it('marks a simulated provider on its card and explains it when selected', async () => {
    useProviders([simulatedProvider]);
    renderWithProviders(<Integrations />);

    expect(await screen.findByText('Simulated tests only')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Example Forge'));
    expect(await screen.findByText(/not against the real service/)).toBeInTheDocument();
  });

  it('shows no simulated badge for a verified provider and names what it was checked against', async () => {
    useProviders([{ ...connectedGithub, verification: 'VERIFIED', verifiedAgainst: 'GitHub REST API' }]);
    await selectGithub();

    expect(await screen.findByText('Checked against: GitHub REST API')).toBeInTheDocument();
    expect(screen.queryByText('Simulated tests only')).not.toBeInTheDocument();
  });

  it('shows nothing when an older backend sends no verification field', async () => {
    useProviders([connectedGithub]);
    await selectGithub();

    await screen.findByText('Connection details');
    expect(screen.queryByText('Simulated tests only')).not.toBeInTheDocument();
    expect(screen.queryByText(/Checked against/)).not.toBeInTheDocument();
  });
});

describe('Integrations — named connections', () => {
  it('shows the connections of the selected provider from the live list', async () => {
    useProviders([{
      ...connectedGithub,
      connections: [
        { name: 'default', status: 'CONNECTED', configured: true, accountEmail: null, accountName: 'octocat', connectedSince: null, scopes: null },
        { name: 'work', status: 'EXPIRED', configured: true, accountEmail: null, accountName: null, connectedSince: null, scopes: null },
      ],
    }]);
    await selectGithub();

    expect(await screen.findByText('Connections')).toBeInTheDocument();
    expect(screen.getByTestId('connection-work')).toHaveTextContent('Expired');
    expect(screen.getByText('Add connection')).toBeInTheDocument();
  });
});

describe('Integrations — HTTP API provider', () => {
  it('shows the setup guide with base-URL and auth-header fields', async () => {
    useProviders([{
      ...connectedGithub,
      providerId: 'http-api',
      displayName: 'HTTP API',
      icon: 'Globe',
      status: 'DISCONNECTED',
      configured: false,
      requiresCredentials: false,
      requiresOAuthFlow: false,
      accountName: null,
      connectedSince: null,
      scopes: null,
      connections: [],
    }]);
    renderWithProviders(<Integrations />);
    fireEvent.click(await screen.findByText('HTTP API'));

    expect(await screen.findByPlaceholderText(/Base URL, e\.g\. https:\/\/api\.example\.com\/v1/)).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/Auth header \(optional\)/)).toHaveAttribute('type', 'password');
    expect(screen.getByText('Several services: named connections')).toBeInTheDocument();
  });
});

// cdccc1e5: Open-Meteo, feeds, Taskwarrior … run without a stored connection — not "Setup required"
describe('Integrations — ready without a connection', () => {
  const credentialFree = (providerId: string, extra: Partial<IntegrationProviderInfo> = {}): IntegrationProviderInfo => ({
    ...connectedGithub,
    providerId,
    displayName: providerId,
    status: 'DISCONNECTED',
    configured: false,
    requiresCredentials: false,
    requiresOAuthFlow: false,
    accountName: null,
    connectedSince: null,
    scopes: null,
    optionalScopes: [],
    connections: [],
    ...extra,
  });

  async function card() {
    renderWithProviders(<Integrations />);
    const [badge] = await screen.findAllByText(/Ready – no sign-in needed|No sign-in – needs `task`|Setup required|Not connected/);
    return badge!.closest('.flex.items-start') as HTMLElement;
  }

  it('shows a provider the new backend marks as connection-optional as ready, with an optional setup', async () => {
    useProviders([credentialFree('feed', { connectionOptional: true })]);
    const shown = await card();

    expect(within(shown).getByText('Ready – no sign-in needed')).toBeInTheDocument();
    expect(within(shown).getByTestId('provider-ready-hint')).toHaveTextContent('Runs in workflows without a stored connection');
    expect(within(shown).getByRole('button', { name: /Optional setup/ })).toBeInTheDocument();
    expect(within(shown).queryByText('Setup required')).toBeNull();
    expect(shown.firstElementChild).not.toHaveClass('bg-warning/10');
  });

  it.each(['open-meteo', 'feed', 'ical-feed', 'ntfy'])(
    'derives ready for %s from the fields an older backend already sends',
    async (providerId) => {
      useProviders([credentialFree(providerId)]);
      const shown = await card();

      expect(within(shown).getByText('Ready – no sign-in needed')).toBeInTheDocument();
    },
  );

  // Nothing checks the host for `task` until the optional setup — the card must not promise more
  it.each([undefined, true])('says Taskwarrior needs the task CLI on the host (connectionOptional: %s)', async (connectionOptional) => {
    useProviders([credentialFree('taskwarrior', { connectionOptional })]);
    const shown = await card();

    expect(within(shown).getByText('No sign-in – needs `task`')).toBeInTheDocument();
    expect(within(shown).getByTestId('provider-ready-hint')).toHaveTextContent('Needs the `task` CLI on the backend host');
    expect(within(shown).queryByText('Ready – no sign-in needed')).toBeNull();
    expect(within(shown).getByRole('button', { name: /Optional setup/ })).toBeInTheDocument();
  });

  it.each([undefined, false])('keeps "Setup required" for the HTTP API, which needs its base URL (connectionOptional: %s)',
    async (connectionOptional) => {
      useProviders([credentialFree('http-api', { connectionOptional })]);
      const shown = await card();
      expect(within(shown).getAllByText('Setup required').length).toBeGreaterThan(0);
      expect(within(shown).queryByText('Ready – no sign-in needed')).toBeNull();
    });

  it('believes the new backend when it says a provider needs its connection', async () => {
    useProviders([credentialFree('ntfy', { connectionOptional: false })]);
    const shown = await card();
    expect(within(shown).getAllByText('Setup required').length).toBeGreaterThan(0);
  });

  // A stored connection that is not connected (Taskwarrior's CLI check failed, or a disconnect) blocks the block
  it('does not call a provider ready while a stored connection is not connected', async () => {
    useProviders([credentialFree('taskwarrior', { connectionOptional: true, connectedSince: '2026-09-20T08:00:00Z' })]);
    const shown = await card();

    expect(within(shown).getAllByText('Setup required').length).toBeGreaterThan(0);
    expect(within(shown).queryByText('No sign-in – needs `task`')).toBeNull();
  });

  it('opens the setup guide from the optional setup, so the connection check stays reachable', async () => {
    useProviders([credentialFree('taskwarrior', { connectionOptional: true })]);
    const shown = await card();
    fireEvent.click(within(shown).getByRole('button', { name: /Optional setup/ }));

    expect(await screen.findByRole('button', { name: /Connect/ })).toBeInTheDocument();
  });
});

describe('Integrations — cards and action test', () => {
  const connectedSlack: IntegrationProviderInfo = {
    ...connectedGithub,
    providerId: 'example-chat',
    displayName: 'Example Chat',
    scopes: null,
    optionalScopes: [],
    actions: [{ name: 'post_message', description: 'Post a message', inputSchema: {}, available: false }],
  };

  it('colours the card icon by status with the theme tokens', async () => {
    useProviders([connectedGithub, { ...connectedSlack, status: 'DISCONNECTED', configured: false }]);
    renderWithProviders(<Integrations />);

    const connectedIcon = (await screen.findByText('GitHub')).closest('.flex.items-start')!.firstElementChild!;
    expect(connectedIcon).toHaveClass('bg-success/10', 'text-success');
    const setupIcon = screen.getByText('Example Chat').closest('.flex.items-start')!.firstElementChild!;
    expect(setupIcon).toHaveClass('bg-warning/10', 'text-warning');
    expect(document.querySelector('[class*="green-500"], [class*="amber-500"]')).toBeNull();
  });

  it('runs an action, shows the result, and starts fresh for another provider', async () => {
    const calls: string[] = [];
    useProviders([connectedGithub, connectedSlack]);
    server.use(http.post(`${BASE}/integrations/:providerId/actions/:action`, ({ params }) => {
      calls.push(`${params.providerId}/${params.action}`);
      return HttpResponse.json({ repos: 2 });
    }));
    await selectGithub();

    const row = (await screen.findByText('list_repos')).closest('.justify-between') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: 'Run' }));
    expect(await screen.findByText('Result')).toBeInTheDocument();
    expect(screen.getByText(/"repos": 2/)).toBeInTheDocument();
    expect(calls).toEqual(['github/list_repos']);

    // Another connected provider: no result carried over; its missing scope is a warning
    fireEvent.click(screen.getByText('Example Chat'));
    expect(await screen.findByText('post_message')).toBeInTheDocument();
    expect(screen.queryByText('Result')).not.toBeInTheDocument();
    expect(screen.getByText('Permission missing — reconnect with the required scope')).toHaveClass('text-warning');
  });
});
