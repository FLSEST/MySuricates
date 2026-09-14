import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, within, fireEvent } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { ModulesCard } from '../ModulesCard';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

function serve(disabled: Parameters<typeof modulesFixture>[1]) {
  server.use(
    http.get('/api/v1/system/capabilities', () => HttpResponse.json(modulesFixture('core', disabled))),
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
});
