import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { useSettingsStore } from '../../../stores/settingsStore';
import { Sidebar } from '../Sidebar';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: false, sidebarItemOrder: [], sidebarHiddenItems: [] });
});

describe('Sidebar modules', () => {
  it('hides pages of modules the backend reports disabled', async () => {
    const modules = modulesFixture('core', ['chains', 'webhooks', 'audit']);
    server.use(http.get('/api/v1/system/capabilities', () => HttpResponse.json(modules)));

    renderWithProviders(<Sidebar />);

    await waitFor(() => expect(screen.queryByRole('link', { name: 'Chains' })).not.toBeInTheDocument(),
      { timeout: 5000 });
    expect(screen.queryByRole('link', { name: 'Webhooks' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Audit Center' })).not.toBeInTheDocument();
    // core pages and still-enabled modules stay
    expect(screen.getByRole('link', { name: 'Workflows' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Todos' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Integrations' })).toBeInTheDocument();
  });

  it('uses the last known modules immediately on reload (no flicker)', () => {
    localStorage.setItem('mysuricates-modules', JSON.stringify(modulesFixture('core', ['chains'])));

    renderWithProviders(<Sidebar />);

    expect(screen.queryByRole('link', { name: 'Chains' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Workflows' })).toBeInTheDocument();
  });

  it('shows module pages when everything is enabled', async () => {
    renderWithProviders(<Sidebar />);

    expect(await screen.findByRole('link', { name: 'Chains' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Webhooks' })).toBeInTheDocument();
  });
});
