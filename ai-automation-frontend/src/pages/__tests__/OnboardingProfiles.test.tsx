import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { de } from '../../i18n/de';
import { useSettingsStore } from '../../stores/settingsStore';
import { OnboardingProfiles } from '../OnboardingProfiles';

vi.mock('../../components/shared/monacoSetup', () => ({ ensureLocalMonaco: () => Promise.resolve(), registerJsonSchema: () => () => {} }));
// The page reaches the library only through the shared wrapper (87c5e9f3); the stand-in shows what it gets.
vi.mock('@monaco-editor/react', () => ({
  default: ({ defaultValue, path, language, options }: {
    defaultValue?: string; path?: string; language?: string; options?: Record<string, unknown>;
  }) => (
    <textarea
      aria-label="editor"
      readOnly
      value={defaultValue ?? ''}
      data-path={path}
      data-language={language}
      data-options={JSON.stringify(options ?? {})}
    />
  ),
}));

function profile(overrides: Record<string, unknown> = {}) {
  return {
    id: 'dev-id', key: 'developer', name: { de: 'Entwickler', en: 'Developer' }, description: { de: '', en: '' },
    content: { de: '## Von Hand', en: '## By hand' }, type: 'PROFILE', builtIn: true, sortOrder: 1, version: 5,
    historyCount: 0, customized: true, updateAvailable: false, createdAt: '2026-09-22T00:00:00Z', updatedAt: '2026-09-22T00:00:00Z',
    ...overrides,
  };
}

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
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

  // Before 87c5e9f3 the page used Monaco directly and controlled: keystrokes got lost, and Enter at
  // the end of a known word accepted a suggestion instead of breaking the line.
  it('edits the content as Markdown prose in the shared editor, one model per language', async () => {
    server.use(http.get('/api/v1/onboarding-profiles', () => HttpResponse.json([profile({ customized: false })])));
    renderWithProviders(<OnboardingProfiles />);

    fireEvent.click(await screen.findByText('Entwickler'));
    const editor = await screen.findByLabelText('editor');
    expect(editor).toHaveValue('## Von Hand');
    expect(editor).toHaveAttribute('data-path', 'onboarding-profile-dev-id-de.md');
    expect(editor).toHaveAttribute('data-language', 'markdown');
    expect(JSON.parse(editor.getAttribute('data-options')!)).toMatchObject({
      lineNumbers: 'off', wordWrap: 'on', quickSuggestions: false, wordBasedSuggestions: 'off',
    });

    fireEvent.click(within(screen.getByRole('group', { name: de.onboardingProfiles.contentLanguage })).getByText('EN'));
    expect(screen.getByLabelText('editor')).toHaveValue('## By hand');
    expect(screen.getByLabelText('editor')).toHaveAttribute('data-path', 'onboarding-profile-dev-id-en.md');
  });

  it('creates a profile with a heading per language and starts empty the next time', async () => {
    const created: unknown[] = [];
    server.use(
      http.get('/api/v1/onboarding-profiles', () => HttpResponse.json([profile()])),
      http.post('/api/v1/onboarding-profiles', async ({ request }) => {
        created.push(await request.json());
        return HttpResponse.json(profile({ id: 'new-id', key: 'my-profile', builtIn: false }));
      }),
    );
    renderWithProviders(<OnboardingProfiles />);

    fireEvent.click(await screen.findByRole('button', { name: de.onboardingProfiles.create }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(de.onboardingProfiles.key), { target: { value: 'My-Profile 2!' } });
    expect(within(dialog).getByLabelText(de.onboardingProfiles.key)).toHaveValue('my-profile2');
    expect(within(dialog).getByRole('button', { name: de.common.create })).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(de.onboardingProfiles.nameEn), { target: { value: 'Tester' } });
    fireEvent.click(within(dialog).getByRole('button', { name: de.common.create }));

    await waitFor(() => expect(created).toEqual([{
      key: 'my-profile2', name: { de: '', en: 'Tester' }, description: { de: '', en: '' },
      content: { de: '## Tester\n\n', en: '## Tester\n\n' }, type: 'PROFILE',
    }]));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: de.onboardingProfiles.create }));
    expect(within(await screen.findByRole('dialog')).getByLabelText(de.onboardingProfiles.key)).toHaveValue('');
  });

  it('deletes an own profile after confirmation and shows the placeholder again', async () => {
    let deleted = '';
    server.use(
      http.get('/api/v1/onboarding-profiles', () => HttpResponse.json([profile({ builtIn: false })])),
      http.delete('/api/v1/onboarding-profiles/:id', ({ params }) => {
        deleted = String(params.id);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithProviders(<OnboardingProfiles />);

    fireEvent.click(await screen.findByText('Entwickler'));
    expect(screen.queryByRole('button', { name: de.onboardingProfiles.reset })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: de.common.delete }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(de.onboardingProfiles.deleteConfirm)).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: de.common.delete }));

    await waitFor(() => expect(deleted).toBe('dev-id'));
    expect(await screen.findByText(de.onboardingProfiles.selectProfile)).toBeTruthy();
  });
});
