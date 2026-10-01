import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { OnboardingCard } from '../OnboardingCard';

function status(overrides: Record<string, unknown> = {}) {
  return {
    enabled: false, format: 'CLAUDE_RULES', profileIds: [], fileExists: false,
    filePath: '.claude/rules/mysuricates.md', language: 'de', fileForeign: false, ...overrides,
  };
}

/** Serves the given status and records every enable request body. */
function serve(current: ReturnType<typeof status>) {
  const enableBodies: Record<string, unknown>[] = [];
  server.use(
    http.get('/api/v1/projects/:id/onboarding/status', () => HttpResponse.json(current)),
    http.get('/api/v1/onboarding-profiles', () => HttpResponse.json([])),
    http.post('/api/v1/projects/:id/onboarding/enable', async ({ request }) => {
      enableBodies.push((await request.json()) as Record<string, unknown>);
      return HttpResponse.json({ ...current, enabled: true });
    }),
  );
  return enableBodies;
}

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'de' }));

describe('OnboardingCard', () => {
  it('switches onboarding on in the language of the UI', async () => {
    useI18nStore.setState({ locale: 'en' });
    const bodies = serve(status());
    renderWithProviders(<OnboardingCard projectId="p1" />);

    fireEvent.click(await screen.findByRole('switch', { name: 'MySuricates Integration' }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ language: 'en', format: 'CLAUDE_RULES', profileIds: [] });
  });

  it('writes the text again in the language chosen for the project', async () => {
    const bodies = serve(status({ enabled: true, language: 'de', fileExists: true }));
    renderWithProviders(<OnboardingCard projectId="p1" />);

    const select = await screen.findByLabelText(de.projects.onboarding.language);
    expect((select as HTMLSelectElement).value).toBe('de');
    fireEvent.change(select, { target: { value: 'en' } });

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ language: 'en', format: 'CLAUDE_RULES' });
  });

  it('warns about a file it did not write instead of calling it written', async () => {
    serve(status({ enabled: true, format: 'AGENTS_MD', fileExists: true, fileForeign: true, filePath: 'AGENTS.md' }));
    renderWithProviders(<OnboardingCard projectId="p1" />);

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('AGENTS.md');
    expect(alert.textContent).toContain('nicht überschrieben');
    expect(screen.queryByText(de.projects.onboarding.installed)).toBeNull();
  });
});
