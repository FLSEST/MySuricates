import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
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
