import { failOnUnhandledRequest } from '../../test/msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import { useSettingsStore } from '../../stores/settingsStore';
import { modulesFixture } from '../../test/modules';
import { Settings } from '../Settings';

// The cards load their own data and are tested on their own; here only the page's sections count
vi.mock('../../components/settings/GeneralSettingsCard', () => ({ GeneralSettingsCard: () => null }));
vi.mock('../../components/settings/AiProviderStatusCard', () => ({ AiProviderStatusCard: () => null }));
vi.mock('../../components/settings/AppearanceCard', () => ({ AppearanceCard: () => null }));
vi.mock('../../components/settings/TraySettingsCard', () => ({ TraySettingsCard: () => null }));
vi.mock('../../components/settings/SecurityCard', () => ({ SecurityCard: () => null }));
vi.mock('../../components/settings/TodoSettingsCard', () => ({ TodoSettingsCard: () => null }));
vi.mock('../../components/settings/RemoteAccessCard', () => ({ RemoteAccessCard: () => null }));
vi.mock('../../components/settings/BackupCard', () => ({ BackupCard: () => null }));
vi.mock('../../components/settings/SystemInfoCard', () => ({ SystemInfoCard: () => null }));
vi.mock('../../components/settings/StagingCard', () => ({ StagingCard: () => null }));
vi.mock('../../components/settings/TestingCard', () => ({ TestingCard: () => null }));
vi.mock('../../components/settings/StagedTargetsCard', () => ({
  StagedTargetsCard: ({ targets }: { targets: Array<{ name: string }> }) => (
    <div data-testid="staged-targets">{targets.map((t) => t.name).join(',')}</div>
  ),
}));
vi.mock('../../components/settings/NotificationsCard', () => ({ NotificationsCard: () => null }));
vi.mock('../../components/settings/ExecutionSettingsCard', () => ({ ExecutionSettingsCard: () => null }));
vi.mock('../../components/settings/WishSettingsCard', () => ({ WishSettingsCard: () => null }));
vi.mock('../../components/settings/ModulesCard', () => ({ ModulesCard: () => null }));
vi.mock('../../components/settings/StartupConfigCard', () => ({ StartupConfigCard: () => null }));
vi.mock('../../components/settings/McpConnectCard', () => ({ McpConnectCard: () => <div data-testid="mcp-connect-card" /> }));
vi.mock('../../components/dashboard/ApiLatencyCard', () => ({ ApiLatencyCard: () => null }));

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

// jsdom has neither: SettingsNav observes the sections, the page scrolls to the linked one
const scrollIntoView = vi.fn();
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: false, advancedMode: false });
  vi.stubGlobal('IntersectionObserver', class { observe() {} unobserve() {} disconnect() {} });
  Element.prototype.scrollIntoView = scrollIntoView;
  scrollIntoView.mockClear();
  server.use(
    http.get('/api/v1/system/request-metrics', () => HttpResponse.json([])),
    http.get('/api/v1/staging/status', () => HttpResponse.json({})),
    http.get('/api/v1/testing/status', () => HttpResponse.json({})),
  );
});
afterEach(() => {
  window.history.replaceState(null, '', '/');
  vi.unstubAllGlobals();
});

// The header of a collapsible section; the navigation carries buttons with the same names
const sectionToggle = (id: string) => within(document.getElementById(id)!).getByRole('button');

describe('Settings — sections and #hash deep link', () => {
  it('opens the section named in the hash from the start and scrolls to it', async () => {
    window.history.replaceState(null, '', '/settings#infrastructure');
    renderWithProviders(<Settings />, { route: '/settings' });

    expect(sectionToggle('infrastructure')).toHaveAttribute('aria-expanded', 'true');
    expect(sectionToggle('execution')).toHaveAttribute('aria-expanded', 'false');
    expect(sectionToggle('system')).toHaveAttribute('aria-expanded', 'false');
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));
    expect(scrollIntoView.mock.contexts[0]).toBe(document.getElementById('infrastructure'));
  });

  it('starts with every collapsible section closed without a hash and toggles one by its header', async () => {
    renderWithProviders(<Settings />, { route: '/settings' });

    for (const id of ['appearance', 'execution', 'infrastructure', 'system']) {
      expect(sectionToggle(id)).toHaveAttribute('aria-expanded', 'false');
    }
    expect(sectionToggle('system')).toHaveTextContent(en.settings.settingsNavSystem);
    fireEvent.click(sectionToggle('system'));
    expect(sectionToggle('system')).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(sectionToggle('system'));
    expect(sectionToggle('system')).toHaveAttribute('aria-expanded', 'false');

    // Give the page's zero-delay scroll timer its chance: without a hash it must not scroll
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('drops the pending scroll when the page unmounts before it ran', async () => {
    window.history.replaceState(null, '', '/settings#infrastructure');
    const { unmount } = renderWithProviders(<Settings />, { route: '/settings' });
    // A section with the linked id still in the document — the next page could carry it too
    const stranger = document.createElement('div');
    stranger.id = 'infrastructure';

    unmount();
    document.body.appendChild(stranger);
    try {
      await new Promise((resolve) => setTimeout(resolve, 10));
      expect(scrollIntoView).not.toHaveBeenCalled();
    } finally {
      stranger.remove();
    }
  });

  // Staged setup (84a78bb7): the targets show without advanced mode; an older backend without the
  // endpoint (404) leaves the section as it was
  it('shows the environments section with the sync targets of the staged setup', async () => {
    server.use(http.get('/api/v1/staging/targets', () => HttpResponse.json([
      { name: 'test', url: 'http://test:9090', keyPresent: true, reachable: true, sealedOnly: false, version: null },
      { name: 'prod', url: 'http://prod:9090', keyPresent: false, reachable: true, sealedOnly: true, version: null },
    ])));
    window.history.replaceState(null, '', '/settings#environments');
    renderWithProviders(<Settings />, { route: '/settings' });

    await waitFor(() => expect(document.getElementById('environments')).not.toBeNull());
    expect(sectionToggle('environments')).toHaveAttribute('aria-expanded', 'true');
    expect(within(document.getElementById('environments')!).getByTestId('staged-targets')).toHaveTextContent('test,prod');
  });

  it('shows the MCP connect section only while the mcp module is on', async () => {
    window.history.replaceState(null, '', '/settings#mcp');
    renderWithProviders(<Settings />, { route: '/settings' });

    await waitFor(() => expect(document.getElementById('mcp')).not.toBeNull());
    expect(sectionToggle('mcp')).toHaveAttribute('aria-expanded', 'true');
    expect(within(document.getElementById('mcp')!).getByTestId('mcp-connect-card')).toBeInTheDocument();
  });

  it('leaves the MCP connect section out when the mcp module is off', async () => {
    localStorage.clear();
    let asked = false;
    server.use(http.get('/api/v1/system/capabilities', () => {
      asked = true;
      return HttpResponse.json({ apiVersion: 'v1', routes: [], blockTypes: [], ...modulesFixture('full', ['mcp']) });
    }));
    renderWithProviders(<Settings />, { route: '/settings' });

    await waitFor(() => expect(asked).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(document.getElementById('mcp')).toBeNull();
    expect(document.querySelector('[data-testid="mcp-connect-card"]')).toBeNull();
  });

  it('leaves the MCP connect section out for an older backend that does not report the module', async () => {
    localStorage.clear();
    let asked = false;
    server.use(http.get('/api/v1/system/capabilities', () => {
      asked = true;
      const fixture = modulesFixture('full');
      fixture.modules = fixture.modules.filter((m) => m.name !== 'mcp');
      return HttpResponse.json({ apiVersion: 'v1', routes: [], blockTypes: [], ...fixture });
    }));
    renderWithProviders(<Settings />, { route: '/settings' });

    await waitFor(() => expect(asked).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(document.getElementById('mcp')).toBeNull();
  });

  it('keeps the environments section hidden without targets or with an older backend', async () => {
    let asked = false;
    server.use(http.get('/api/v1/staging/targets', () => {
      asked = true;
      return HttpResponse.json({ error: 'No static resource' }, { status: 404 });
    }));
    renderWithProviders(<Settings />, { route: '/settings' });

    await waitFor(() => expect(asked).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(document.getElementById('environments')).toBeNull();
  });
});
