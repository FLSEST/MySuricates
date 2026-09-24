import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useI18nStore } from '../../i18n';
import { useOnboardingProfileEditor } from '../useOnboardingProfileEditor';
import type { OnboardingProfile } from '../useOnboardingProfiles';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'de' }));

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: qc }, children);
}

function profile(overrides: Partial<OnboardingProfile> = {}): OnboardingProfile {
  return {
    id: 'dev-id', key: 'developer', name: { de: 'Entwickler', en: 'Developer' }, description: { de: 'Für Entwickler', en: '' },
    content: { de: '## Von Hand', en: '## By hand' }, type: 'PROFILE', builtIn: true, sortOrder: 1, version: 5,
    historyCount: 0, customized: true, updateAvailable: false, createdAt: '2026-09-22T00:00:00Z', updatedAt: '2026-09-22T00:00:00Z',
    ...overrides,
  };
}

function renderEditor(profiles: OnboardingProfile[] = [profile()]) {
  return renderHook(({ list }) => useOnboardingProfileEditor(list), {
    wrapper: createWrapper(), initialProps: { list: profiles },
  });
}

describe('useOnboardingProfileEditor', () => {
  it('loads the draft from the selected profile and starts in the app language', () => {
    const { result } = renderEditor();
    expect(result.current.selected).toBeNull();
    expect(result.current.contentLanguage).toBe('de');

    act(() => result.current.select(profile()));
    expect(result.current.selected?.id).toBe('dev-id');
    expect(result.current.draft).toEqual({
      name: { de: 'Entwickler', en: 'Developer' },
      description: { de: 'Für Entwickler', en: '' },
      content: { de: '## Von Hand', en: '## By hand' },
    });
    expect(result.current.hasChanges).toBe(false);
  });

  it('starts in English for any other app language', () => {
    useI18nStore.setState({ locale: 'en' });
    expect(renderEditor().result.current.contentLanguage).toBe('en');
  });

  it('marks changes and writes the content of the language being edited', () => {
    const { result } = renderEditor();
    act(() => result.current.select(profile()));

    act(() => result.current.setContentLanguage('en'));
    act(() => result.current.setContent('## Changed'));
    expect(result.current.draft.content).toEqual({ de: '## Von Hand', en: '## Changed' });
    expect(result.current.hasChanges).toBe(true);

    // Choosing a profile again drops unsaved changes but keeps the content language
    act(() => result.current.select(profile()));
    expect(result.current.hasChanges).toBe(false);
    expect(result.current.draft.content.en).toBe('## By hand');
    expect(result.current.contentLanguage).toBe('en');

    act(() => result.current.setName('en', 'Dev'));
    act(() => result.current.setDescription('en', 'For developers'));
    expect(result.current.draft.name).toEqual({ de: 'Entwickler', en: 'Dev' });
    expect(result.current.draft.description).toEqual({ de: 'Für Entwickler', en: 'For developers' });
  });

  it('saves name, description and both contents, then has no changes', async () => {
    const bodies: unknown[] = [];
    server.use(http.put('/api/v1/onboarding-profiles/:id', async ({ request }) => {
      bodies.push(await request.json());
      return HttpResponse.json(profile());
    }));
    const { result } = renderEditor();
    act(() => result.current.select(profile()));
    act(() => result.current.setContent('## Neu'));

    act(() => result.current.save());
    await waitFor(() => expect(result.current.hasChanges).toBe(false));
    expect(bodies).toEqual([{
      name: { de: 'Entwickler', en: 'Developer' },
      description: { de: 'Für Entwickler', en: '' },
      content: { de: '## Neu', en: '## By hand' },
    }]);
  });

  it('deletes the selected profile and clears the selection', async () => {
    server.use(http.delete('/api/v1/onboarding-profiles/:id', () => new HttpResponse(null, { status: 204 })));
    const onDone = vi.fn();
    const { result } = renderEditor();
    act(() => result.current.select(profile()));

    act(() => result.current.remove(onDone));
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(result.current.selected).toBeNull();
  });

  it('takes over the shipped text and shows it in the editor', async () => {
    server.use(http.post('/api/v1/onboarding-profiles/:id/reset', () =>
      HttpResponse.json(profile({ customized: false, content: { de: '## Neue Fassung', en: '## New version' } }))));
    const onDone = vi.fn();
    const { result } = renderEditor();
    act(() => result.current.select(profile()));
    act(() => result.current.setContent('## Entwurf'));

    act(() => result.current.resetToShipped(onDone));
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(result.current.draft.content).toEqual({ de: '## Neue Fassung', en: '## New version' });
    expect(result.current.hasChanges).toBe(false);
  });

  it('follows the live list for the selected profile', () => {
    const { result, rerender } = renderEditor();
    act(() => result.current.select(profile()));
    rerender({ list: [profile({ version: 6 })] });
    expect(result.current.selected?.version).toBe(6);
    rerender({ list: [] });
    expect(result.current.selected).toBeNull();
  });
});
