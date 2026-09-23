import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import { AiProviderStatusCard } from '../AiProviderStatusCard';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
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
