import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { de } from '../../i18n/de';
import { useSettingsStore } from '../../stores/settingsStore';
import { OnboardingProfiles } from '../OnboardingProfiles';

vi.mock('../../components/shared/monacoSetup', () => ({ ensureLocalMonaco: () => Promise.resolve() }));
vi.mock('@monaco-editor/react', () => ({
  default: ({ value }: { value: string }) => <textarea aria-label="editor" readOnly value={value} />,
}));

function profile(overrides: Record<string, unknown> = {}) {
  return {
    id: 'dev-id', key: 'developer', name: { de: 'Entwickler', en: 'Developer' }, description: { de: '', en: '' },
    content: { de: '## Von Hand', en: '## By hand' }, type: 'PROFILE', builtIn: true, sortOrder: 1, version: 5,
    historyCount: 0, customized: true, updateAvailable: false, createdAt: '2026-09-22T00:00:00Z', updatedAt: '2026-09-22T00:00:00Z',
    ...overrides,
  };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'de' });
  useSettingsStore.setState({ hintsEnabled: false });
});

describe('OnboardingProfiles', () => {
  it('marks an edited built-in profile as customized', async () => {
    server.use(http.get('/api/v1/onboarding-profiles', () => HttpResponse.json([profile()])));
    renderWithProviders(<OnboardingProfiles />);

    expect(await screen.findByText(de.onboardingProfiles.customized)).toBeTruthy();
    expect(screen.queryByText(de.onboardingProfiles.updateAvailable)).toBeNull();
  });

  it('offers a newer shipped text and takes it over on request', async () => {
    let resetCalls = 0;
    server.use(
      http.get('/api/v1/onboarding-profiles', () => HttpResponse.json([profile({ updateAvailable: true })])),
      http.get('/api/v1/onboarding-profiles/:id/shipped', () =>
        HttpResponse.json({ version: 4, content: { de: '## Neue Fassung', en: '## New version' } })),
      http.post('/api/v1/onboarding-profiles/:id/reset', () => {
        resetCalls++;
        return HttpResponse.json(profile({ customized: false, content: { de: '## Neue Fassung', en: '## New version' } }));
      }),
    );
    renderWithProviders(<OnboardingProfiles />);

    fireEvent.click(await screen.findByText('Entwickler'));
    expect(screen.getByRole('status').textContent).toContain(de.onboardingProfiles.updateAvailableHint);

    fireEvent.click(screen.getByRole('button', { name: de.onboardingProfiles.showShipped }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('## Neue Fassung')).toBeTruthy();

    fireEvent.click(within(dialog).getByRole('button', { name: de.onboardingProfiles.takeOver }));
    await waitFor(() => expect(resetCalls).toBe(1));
  });

  it('edits the description in both languages', async () => {
    server.use(http.get('/api/v1/onboarding-profiles', () => HttpResponse.json([profile({ customized: false })])));
    renderWithProviders(<OnboardingProfiles />);

    fireEvent.click(await screen.findByText('Entwickler'));

    expect(screen.getByLabelText(de.onboardingProfiles.descriptionDe)).toBeTruthy();
    expect(screen.getByLabelText(de.onboardingProfiles.descriptionEn)).toBeTruthy();
  });
});
