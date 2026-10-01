import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import { de } from '../../../i18n/de';
import { AiProviderStatusCard } from '../AiProviderStatusCard';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

describe('AiProviderStatusCard', () => {
  it('always shows the status in the settings, with where each login was found — never a value', async () => {
    server.use(http.get('/api/v1/system/ai-providers/status', () => HttpResponse.json({
      container: false,
      checkedAt: '2026-09-24T10:00:00Z',
      providers: [
        { id: 'CLAUDE', cli: 'claude', state: 'READY', installed: true, loggedIn: true, loginVia: 'ENVIRONMENT',
          loginSource: 'CLAUDE_CODE_OAUTH_TOKEN', install: 'curl claude', login: 'claude auth login', defaultProvider: true },
        { id: 'CODEX', cli: 'codex', state: 'READY', installed: true, loggedIn: true, loginVia: 'FILE',
          loginSource: '$CODEX_HOME/auth.json', install: 'npm install -g @openai/codex', login: 'codex login', defaultProvider: false },
      ],
    })));
    renderWithProviders(<AiProviderStatusCard />);

    const card = await screen.findByTestId('ai-provider-status-card');
    expect(card).toHaveTextContent(en.aiProviderStatus.title);
    expect(card).toHaveTextContent(en.aiProviderStatus.heuristic);
    expect(await within(card).findByTestId('ai-provider-CLAUDE')).toHaveTextContent('login via CLAUDE_CODE_OAUTH_TOKEN');
    expect(within(card).getByTestId('ai-provider-CODEX')).toHaveTextContent('login in $CODEX_HOME/auth.json');
    // Everything ready: no command to copy, only the button to look again
    expect(within(card).queryByRole('button', { name: /copy/i })).not.toBeInTheDocument();
    expect(within(card).getByRole('button', { name: en.aiProviderStatus.recheck })).toBeInTheDocument();
  });

  // eab43a8b: pi's hint after the login command came from the catalog in English, also in the German UI
  it('shows what follows the login command in the interface language; only the command is copied', async () => {
    useI18nStore.setState({ locale: 'de' });
    server.use(http.get('/api/v1/system/ai-providers/status', () => HttpResponse.json({
      container: false,
      checkedAt: '2026-09-24T10:00:00Z',
      providers: [
        { id: 'PI', cli: 'pi', state: 'LOGIN_MISSING', installed: true, loggedIn: false, loginVia: null,
          loginSource: null, install: 'npm install -g pi', login: 'pi', defaultProvider: true, loginNote: 'pi_slash_login' },
        { id: 'CODEX', cli: 'codex', state: 'LOGIN_MISSING', installed: true, loggedIn: false, loginVia: null,
          loginSource: null, install: 'npm install -g @openai/codex', login: 'codex login', defaultProvider: false,
          loginNote: 'from_a_newer_backend' },
      ],
    })));
    renderWithProviders(<AiProviderStatusCard />);

    const pi = await screen.findByTestId('ai-provider-PI');
    expect(within(pi).getByTestId('ai-provider-login-note')).toHaveTextContent(de.aiProviderStatus.loginNotes.pi_slash_login);
    expect(pi).not.toHaveTextContent('then /login');
    expect(within(pi).getByText('pi', { selector: 'code' })).toBeInTheDocument();
    // An unknown key shows nothing rather than the key; the command stays
    const codex = screen.getByTestId('ai-provider-CODEX');
    expect(within(codex).queryByTestId('ai-provider-login-note')).not.toBeInTheDocument();
    expect(codex).not.toHaveTextContent('from_a_newer_backend');
    expect(within(codex).getByText('codex login', { selector: 'code' })).toBeInTheDocument();
  });

  it('an older backend without loginNote: its login text as before', async () => {
    const legacy = 'pi, then /login   (or an API key as environment variable, see pi --help)';
    server.use(http.get('/api/v1/system/ai-providers/status', () => HttpResponse.json({
      container: false,
      checkedAt: '2026-09-24T10:00:00Z',
      providers: [
        { id: 'PI', cli: 'pi', state: 'LOGIN_MISSING', installed: true, loggedIn: false, loginVia: null,
          loginSource: null, install: 'npm install -g pi', login: legacy, defaultProvider: true },
      ],
    })));
    renderWithProviders(<AiProviderStatusCard />);

    const pi = await screen.findByTestId('ai-provider-PI');
    expect(pi.querySelector('code')?.textContent).toBe(legacy);
    expect(within(pi).queryByTestId('ai-provider-login-note')).not.toBeInTheDocument();
  });

  it('in a container, shows the login command of the start path that launched it, copyable', async () => {
    server.use(http.get('/api/v1/system/ai-providers/status', () => HttpResponse.json({
      container: true,
      loginCommand: 'scripts/instance.sh login',
      checkedAt: '2026-09-24T10:00:00Z',
      providers: [
        { id: 'CLAUDE', cli: 'claude', state: 'LOGIN_MISSING', installed: true, loggedIn: false, loginVia: null,
          loginSource: null, install: 'curl claude', login: 'claude auth login', defaultProvider: true },
      ],
    })));
    renderWithProviders(<AiProviderStatusCard />);

    const card = await screen.findByTestId('ai-provider-status-card');
    const note = await within(card).findByTestId('ai-provider-status-container');
    expect(within(note).getByText('scripts/instance.sh login', { selector: 'code' })).toBeInTheDocument();
    // The per-provider command never shows in a container — it would point to the wrong machine
    expect(within(card).queryByText('claude auth login')).not.toBeInTheDocument();
  });

  it('in a container without a known login command (older backend, or the default login mode): the hint only', async () => {
    server.use(http.get('/api/v1/system/ai-providers/status', () => HttpResponse.json({
      container: true,
      checkedAt: '2026-09-24T10:00:00Z',
      providers: [
        { id: 'CLAUDE', cli: 'claude', state: 'LOGIN_MISSING', installed: true, loggedIn: false, loginVia: null,
          loginSource: null, install: 'curl claude', login: 'claude auth login', defaultProvider: true },
      ],
    })));
    renderWithProviders(<AiProviderStatusCard />);

    const card = await screen.findByTestId('ai-provider-status-card');
    const note = await within(card).findByTestId('ai-provider-status-container');
    expect(note).toHaveTextContent(en.aiProviderStatus.containerNote);
    expect(within(note).queryByRole('button', { name: /copy/i })).not.toBeInTheDocument();
  });

  it('shows nothing when the instance cannot answer (older backend)', async () => {
    let asked = false;
    server.use(http.get('/api/v1/system/ai-providers/status', () => {
      asked = true;
      return new HttpResponse(null, { status: 404 });
    }));
    const { queryClient } = renderWithProviders(<AiProviderStatusCard />);

    await expect.poll(() => asked).toBe(true);
    await expect.poll(() => queryClient.getQueryState(['system', 'ai-providers', 'status'])?.status).toBe('error');
    expect(screen.queryByTestId('ai-provider-status-card')).not.toBeInTheDocument();
  });
});
