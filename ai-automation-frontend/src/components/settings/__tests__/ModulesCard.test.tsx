import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, within, fireEvent, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { moduleText } from '../../../i18n/modules';
import type { ModuleName } from '../../../types';
import { ModulesCard } from '../ModulesCard';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

function serve(disabled: Parameters<typeof modulesFixture>[1], uiEditable = false) {
  server.use(
    http.get('/api/v1/system/capabilities', () => HttpResponse.json(modulesFixture('core', disabled, uiEditable))),
  );
}

describe('ModulesCard', () => {
  it('shows the preset and both groups without any toggle', async () => {
    serve(['webhooks']);
    renderWithProviders(<ModulesCard />);

    expect(await screen.findByText('core — core platform only')).toBeInTheDocument();
    expect(screen.getByText('Feature modules')).toBeInTheDocument();
    expect(screen.getByText('Workflow hardening')).toBeInTheDocument();
    expect(screen.queryByRole('switch')).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });

  it('explains how to allow switching here and why hardening never is', async () => {
    serve([]);
    renderWithProviders(<ModulesCard />);

    expect(await screen.findByTestId('hardening-note')).toHaveTextContent('protects your workflows against someone who holds the API key');
    expect(screen.getByText(/allow switching here/)).toBeInTheDocument();
    expect(screen.getByText(/ui-editable: true/)).toBeInTheDocument();
    expect(screen.getByText(/anyone with the API key could then turn on webhooks/)).toBeInTheDocument();
  });

  it('offers the enable snippet for a disabled module', async () => {
    serve(['webhooks']);
    renderWithProviders(<ModulesCard />);

    const row = await screen.findByTestId('module-webhooks');
    expect(within(row).getByText('Off')).toBeInTheDocument();
    expect(within(row).getByText('network')).toBeInTheDocument();

    fireEvent.click(within(row).getByRole('button', { expanded: false }));

    expect(within(row).getByText('Enable')).toBeInTheDocument();
    expect(within(row).getByText(/webhooks: true/)).toBeInTheDocument();
    expect(within(row).getByText('AUTOMATION_MODULES_WEBHOOKS=true')).toBeInTheDocument();
  });

  it('offers the disable snippet and dependencies for an enabled module', async () => {
    serve([]);
    renderWithProviders(<ModulesCard />);

    const row = await screen.findByTestId('module-wishes');
    fireEvent.click(within(row).getByRole('button', { expanded: false }));

    expect(within(row).getByText('Disable')).toBeInTheDocument();
    expect(within(row).getByText('Requires: Chains')).toBeInTheDocument();
    expect(within(row).getByText('AUTOMATION_MODULES_WISHES=false')).toBeInTheDocument();
  });

  it('describes a known module in the selected language, not in the backend English', async () => {
    useI18nStore.setState({ locale: 'de' });
    serve([]);
    renderWithProviders(<ModulesCard />);

    const row = await screen.findByTestId('module-chains');
    fireEvent.click(within(row).getByRole('button', { expanded: false }));

    expect(within(row).getByText(moduleText('chains', 'de').description)).toBeInTheDocument();
    expect(within(row).queryByText('chains description')).not.toBeInTheDocument();
  });

  it('falls back to the backend description for a module this build does not know', async () => {
    const fixture = modulesFixture('core', [], false);
    fixture.modules = [
      ...fixture.modules,
      { ...fixture.modules[0], name: 'future-module' as ModuleName, description: 'A module added after this UI build' },
    ];
    server.use(http.get('/api/v1/system/capabilities', () => HttpResponse.json(fixture)));
    renderWithProviders(<ModulesCard />);

    const row = await screen.findByTestId('module-future-module');
    fireEvent.click(within(row).getByRole('button', { expanded: false }));

    expect(within(row).getByText('A module added after this UI build')).toBeInTheDocument();
  });

  describe('with automation.modules.ui-editable', () => {
    it('offers a switch per feature module only and applies the result immediately', async () => {
      serve(['webhooks'], true);
      let body: unknown;
      server.use(http.put('/api/v1/system/modules/webhooks', async ({ request }) => {
        body = await request.json();
        const next = modulesFixture('core', [], true);
        next.modules = next.modules.map((m) => (m.name === 'webhooks' ? { ...m, configured: false, overridden: true } : m));
        return HttpResponse.json(next);
      }));
      renderWithProviders(<ModulesCard />);

      expect(await screen.findByTestId('modules-ui-editable')).toBeInTheDocument();
      expect(within(screen.getByTestId('module-provenance')).queryByRole('switch')).not.toBeInTheDocument();
      const row = screen.getByTestId('module-webhooks');
      fireEvent.click(within(row).getByRole('switch', { name: 'Switch Webhooks' }));

      await waitFor(() => expect(within(screen.getByTestId('module-webhooks')).getByText('changed here')).toBeInTheDocument());
      expect(body).toEqual({ enabled: true });
      expect(within(screen.getByTestId('module-webhooks')).getByRole('switch')).toHaveAttribute('aria-checked', 'true');
    });

    it('resets an override to the configuration', async () => {
      const fixture = modulesFixture('core', [], true);
      fixture.modules = fixture.modules.map((m) => (m.name === 'chains' ? { ...m, configured: false, overridden: true } : m));
      server.use(http.get('/api/v1/system/capabilities', () => HttpResponse.json(fixture)));
      let resets = 0;
      server.use(http.delete('/api/v1/system/modules/chains/override', () => {
        resets += 1;
        return HttpResponse.json(modulesFixture('core', ['chains'], true));
      }));
      renderWithProviders(<ModulesCard />);

      const row = await screen.findByTestId('module-chains');
      await waitFor(() => expect(within(row).getByText('changed here')).toBeInTheDocument());
      fireEvent.click(within(row).getByRole('button', { expanded: false }));
      expect(within(row).getByText('Configuration: Off')).toBeInTheDocument();
      fireEvent.click(within(row).getByRole('button', { name: 'Reset to configuration' }));

      await waitFor(() => expect(resets).toBe(1));
    });
  });
});
