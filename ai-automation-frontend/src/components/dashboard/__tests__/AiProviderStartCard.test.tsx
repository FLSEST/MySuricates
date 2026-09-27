import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import { de } from '../../../i18n/de';
import { useSettingsStore } from '../../../stores/settingsStore';
import { useAiProviderStatus, type AiProviderStatus, type AiProviderStatusReport } from '../../../hooks/useSystem';
import { useProjects } from '../../../hooks/useProjects';
import { AiProviderStartCard } from '../AiProviderStartCard';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ aiStatusDismissed: '' });
});

const PROJECT = {
  id: 'p1', name: 'Mine', type: 'CODE', path: '/tmp/mine', status: 'STOPPED', container: null,
  workflowCount: 0, createdAt: '2026-09-24T00:00:00Z', updatedAt: '2026-09-24T00:00:00Z',
};

function provider(id: string, state: AiProviderStatus['state'], extra: Partial<AiProviderStatus> = {}): AiProviderStatus {
  const ready = state === 'READY';
  return {
    id,
    cli: id.toLowerCase(),
    state,
    installed: state !== 'NOT_INSTALLED',
    loggedIn: ready,
    loginVia: ready ? 'FILE' : null,
    loginSource: ready ? `~/.${id.toLowerCase()}/auth.json` : null,
    install: `npm install -g ${id.toLowerCase()}`,
    login: `${id.toLowerCase()} login`,
    defaultProvider: false,
    ...extra,
  };
}

function report(providers: AiProviderStatus[], container = false, loginCommand: string | null = null): AiProviderStatusReport {
  return { container, loginCommand, checkedAt: '2026-09-24T10:00:00Z', providers };
}

function serve(status: AiProviderStatusReport, projects: unknown[] = []) {
  server.use(
    http.get('/api/v1/system/ai-providers/status', () => HttpResponse.json(status)),
    http.get('/api/v1/projects', () => HttpResponse.json(projects)),
  );
}

/**
 * Renders next to the card and shows up in the same render pass in which the card gets its data:
 * a card that is still missing after this appeared stays away on purpose, not for lack of data.
 */
function Loaded() {
  const { data: status } = useAiProviderStatus();
  const { data: projects } = useProjects();
  return status && projects ? <span data-testid="loaded" /> : null;
}

function renderCard() {
  return renderWithProviders(<><AiProviderStartCard /><Loaded /></>);
}

const NOTHING_LOGGED_IN = report([
  provider('CLAUDE', 'LOGIN_MISSING', { defaultProvider: true }),
  provider('CODEX', 'NOT_INSTALLED'),
  provider('PI', 'READY'),
]);

describe('AiProviderStartCard', () => {
  it('tells a newcomer what is missing, with the command — before the first run fails', async () => {
    serve(NOTHING_LOGGED_IN);
    renderWithProviders(<AiProviderStartCard />);

    const card = await screen.findByTestId('ai-provider-start');
    expect(card).toHaveTextContent(en.aiProviderStatus.titleNotReady);
    expect(card).toHaveTextContent(en.aiProviderStatus.intro);

    const claude = within(card).getByTestId('ai-provider-CLAUDE');
    expect(claude).toHaveTextContent(en.aiProviderStatus.states.LOGIN_MISSING);
    expect(claude).toHaveTextContent(en.aiProviderStatus.defaultBadge);
    expect(within(claude).getByText('claude login').tagName).toBe('CODE');
    expect(within(claude).queryByText('npm install -g claude')).not.toBeInTheDocument();

    // A missing program needs installing first, then the login
    const codex = within(card).getByTestId('ai-provider-CODEX');
    expect(codex).toHaveTextContent(en.aiProviderStatus.states.NOT_INSTALLED);
    expect(within(codex).getByText('npm install -g codex')).toBeInTheDocument();
    expect(within(codex).getByText('codex login')).toBeInTheDocument();

    const pi = within(card).getByTestId('ai-provider-PI');
    expect(pi).toHaveTextContent(en.aiProviderStatus.states.READY);
    expect(pi).toHaveTextContent('~/.pi/auth.json');
    expect(within(pi).queryByRole('button', { name: /copy/i })).not.toBeInTheDocument();

    // The default provider comes first: it is the one that fails a run
    const rows = within(card).getAllByRole('listitem');
    expect(rows[0]).toHaveAttribute('data-testid', 'ai-provider-CLAUDE');
  });

  it('says "ready" to a newcomer once the default provider is logged in', async () => {
    serve(report([provider('CLAUDE', 'READY', { defaultProvider: true }), provider('CODEX', 'NOT_INSTALLED')]));
    renderWithProviders(<AiProviderStartCard />);

    const card = await screen.findByTestId('ai-provider-start');
    expect(card).toHaveTextContent(en.aiProviderStatus.titleReady);
    expect(within(card).getByTestId('ai-provider-CLAUDE')).toHaveTextContent(en.aiProviderStatus.states.READY);
  });

  it('stays away once there is a project and the default provider is ready', async () => {
    serve(report([provider('CLAUDE', 'READY', { defaultProvider: true }), provider('PI', 'NOT_INSTALLED')]), [PROJECT]);
    renderCard();

    await screen.findByTestId('loaded');
    expect(screen.queryByTestId('ai-provider-start')).not.toBeInTheDocument();
  });

  it('comes back for everyone whose default provider is not ready, projects or not', async () => {
    serve(NOTHING_LOGGED_IN, [PROJECT]);
    renderWithProviders(<AiProviderStartCard />);

    expect(await screen.findByTestId('ai-provider-start')).toHaveTextContent(en.aiProviderStatus.titleNotReady);
  });

  it('names no command in a container, where it would point to the wrong machine', async () => {
    serve(report([provider('CLAUDE', 'LOGIN_MISSING', { defaultProvider: true })], true));
    renderWithProviders(<AiProviderStartCard />);

    const card = await screen.findByTestId('ai-provider-start');
    expect(within(card).queryByText('claude login')).not.toBeInTheDocument();
    expect(within(card).getByTestId('ai-provider-status-container')).toHaveTextContent(en.aiProviderStatus.containerNote);
  });

  // Whether a login command is known depends on the start path (tryout, instance.sh in programs
  // mode, staging.sh in programs mode) — the backend sends it, the card shows it copyable.
  it('shows the login command of the start path that launched this container, copyable', async () => {
    serve(report([provider('CLAUDE', 'LOGIN_MISSING', { defaultProvider: true })], true,
      'scripts/tryout.sh login [claude|codex|pi]'));
    renderWithProviders(<AiProviderStartCard />);

    const card = await screen.findByTestId('ai-provider-start');
    const note = within(card).getByTestId('ai-provider-status-container');
    expect(within(note).getByText('scripts/tryout.sh login [claude|codex|pi]', { selector: 'code' })).toBeInTheDocument();
  });

  it('an older backend without loginCommand: the generic hint only, as before', async () => {
    const { loginCommand: _drop, ...legacyReport } = report(
      [provider('CLAUDE', 'LOGIN_MISSING', { defaultProvider: true })], true,
    );
    serve(legacyReport as AiProviderStatusReport);
    renderWithProviders(<AiProviderStartCard />);

    const card = await screen.findByTestId('ai-provider-start');
    const note = within(card).getByTestId('ai-provider-status-container');
    expect(note).toHaveTextContent(en.aiProviderStatus.containerNote);
    expect(within(note).queryByRole('button', { name: /copy/i })).not.toBeInTheDocument();
  });

  it('stays hidden for the status it was hidden at, and returns when the status changes', async () => {
    serve(NOTHING_LOGGED_IN, [PROJECT]);
    const first = renderWithProviders(<AiProviderStartCard />);

    fireEvent.click(await screen.findByRole('button', { name: en.aiProviderStatus.dismiss }));
    expect(screen.queryByTestId('ai-provider-start')).not.toBeInTheDocument();
    first.unmount();

    // Same status on the next visit: still hidden
    const second = renderCard();
    await screen.findByTestId('loaded');
    expect(screen.queryByTestId('ai-provider-start')).not.toBeInTheDocument();
    second.unmount();

    // The default provider lost its program meanwhile: a new status, the card is back
    serve(report([
      provider('CLAUDE', 'NOT_INSTALLED', { defaultProvider: true }),
      provider('CODEX', 'NOT_INSTALLED'),
      provider('PI', 'READY'),
    ]), [PROJECT]);
    renderWithProviders(<AiProviderStartCard />);
    expect(await screen.findByTestId('ai-provider-start')).toBeInTheDocument();
  });

  it('checks again on request — right after logging in', async () => {
    let refreshed = false;
    serve(NOTHING_LOGGED_IN);
    server.use(http.post('/api/v1/system/ai-providers/status/refresh', () => {
      refreshed = true;
      return HttpResponse.json(report([provider('CLAUDE', 'READY', { defaultProvider: true })]));
    }));
    renderWithProviders(<AiProviderStartCard />);

    fireEvent.click(await screen.findByRole('button', { name: en.aiProviderStatus.recheck }));

    await waitFor(() => expect(refreshed).toBe(true));
    await waitFor(() => expect(screen.getByTestId('ai-provider-start')).toHaveTextContent(en.aiProviderStatus.titleReady));
  });

  it('speaks German', async () => {
    useI18nStore.setState({ locale: 'de' });
    serve(NOTHING_LOGGED_IN);
    renderWithProviders(<AiProviderStartCard />);

    const card = await screen.findByTestId('ai-provider-start');
    expect(card).toHaveTextContent(de.aiProviderStatus.titleNotReady);
    expect(within(card).getByTestId('ai-provider-CLAUDE')).toHaveTextContent(de.aiProviderStatus.states.LOGIN_MISSING);
    expect(within(card).getByTestId('ai-provider-CODEX')).toHaveTextContent(de.aiProviderStatus.install);
  });
});
