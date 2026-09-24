import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { useSettingsStore } from '../../../stores/settingsStore';
import { TemplateSelector } from '../TemplateSelector';
import type { ModuleName, ModulePreset } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ hintsEnabled: true, dismissedHints: {} });
});

const HINT = '[data-hint="build-from-description"]';

/**
 * The module state is known from the first render (browser cache, as after any earlier visit) and
 * confirmed by the server — without it, "missing" would pass while the state is still unknown.
 */
async function renderWith(preset: ModulePreset, off: ModuleName[]) {
  const modules = modulesFixture(preset, off);
  localStorage.setItem('mysuricates-modules', JSON.stringify(modules));
  let served = false;
  server.use(
    http.get('/api/v1/workflow-templates', () => HttpResponse.json([])),
    http.get('/api/v1/system/capabilities', () => {
      served = true;
      return HttpResponse.json({ blockTypes: [], ...modules });
    }),
  );
  const { container } = renderWithProviders(<TemplateSelector onSelect={() => {}} />);
  await waitFor(() => expect(served).toBe(true));
  await screen.findByRole('button', { name: 'Templates' });
  return container;
}

/** 3f96f7a0: the ways to build from a description exist, even where this instance has them off. */
describe('TemplateSelector — hint on building from a description', () => {
  it('appears on core, where assistant, factory and wishes are all off', async () => {
    const container = await renderWith('core', ['assistant', 'factory', 'wishes']);

    expect(container.querySelector(HINT)).not.toBeNull();
    expect(screen.getByText('Building from a description is off here')).toBeInTheDocument();
    expect(container.querySelector(HINT)).toHaveTextContent('--preset research --force');
  });

  it('is missing on research, where the assistant is on', async () => {
    const container = await renderWith('research', ['factory', 'wishes']);

    expect(container.querySelector(HINT)).toBeNull();
  });

  it('is missing on full', async () => {
    const container = await renderWith('full', []);

    expect(container.querySelector(HINT)).toBeNull();
  });

  it('is missing as soon as one of the three is switched on', async () => {
    const container = await renderWith('core', ['assistant', 'wishes']);

    expect(container.querySelector(HINT)).toBeNull();
  });
});
