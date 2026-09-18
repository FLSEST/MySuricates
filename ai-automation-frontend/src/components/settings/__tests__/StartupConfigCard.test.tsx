import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { StartupConfigCard } from '../StartupConfigCard';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

function serve(disabled: Parameters<typeof modulesFixture>[1], uiEditable = false) {
  server.use(
    http.get('/api/v1/system/capabilities', () => HttpResponse.json(modulesFixture('full', disabled, uiEditable))),
  );
}

describe('StartupConfigCard', () => {
  it('renders the preset, module states, ui-editable (off) and the setup.sh command, read-only', async () => {
    serve(['webhooks'], false);
    renderWithProviders(<StartupConfigCard />);

    const section = await screen.findByTestId('startup-config');
    expect(await within(section).findByText('full — every module')).toBeInTheDocument();

    // Two feature modules: one on, one off
    expect(within(section).getByTestId('startup-config-module-chains')).toHaveTextContent('Chains');
    expect(within(section).getByTestId('startup-config-module-chains')).toHaveTextContent('On');
    expect(within(section).getByTestId('startup-config-module-webhooks')).toHaveTextContent('Webhooks');
    expect(within(section).getByTestId('startup-config-module-webhooks')).toHaveTextContent('Off');

    // All four hardening switches, all enabled by default fixture
    expect(within(section).getByTestId('startup-config-module-provenance')).toHaveTextContent('On');
    expect(within(section).getByTestId('startup-config-module-sealing')).toHaveTextContent('On');
    expect(within(section).getByTestId('startup-config-module-require-sealed-for-unattended')).toHaveTextContent('On');
    expect(within(section).getByTestId('startup-config-module-webhook-signatures')).toHaveTextContent('On');

    // ui-editable is off: state shown, but no security hint
    expect(within(section).getByTestId('startup-config-ui-editable')).toHaveTextContent('Off');
    expect(within(section).queryByTestId('startup-config-ui-editable-risk')).not.toBeInTheDocument();

    // The setup.sh command is shown, copyable — with --force, which is what a running instance
    // needs: its config/application.yml already exists (729ceb19).
    expect(within(section).getByText(/scripts\/setup\.sh --preset .*--force/)).toBeInTheDocument();
    expect(within(section).getByTestId('startup-config-setup-hint'))
      .toHaveTextContent(/--force.*automation\.modules\.preset/);
    expect(within(section).getByTestId('startup-config-setup-hint')).toHaveTextContent(/config\/application\.yml/);

    // Read-only: no button/input anywhere in the section except the copy button
    const buttons = within(section).getAllByRole('button');
    expect(buttons).toHaveLength(1);
    expect(buttons[0]).toHaveAccessibleName(/copy/i);
    expect(within(section).queryAllByRole('switch')).toHaveLength(0);
    expect(within(section).queryAllByRole('checkbox')).toHaveLength(0);
    expect(within(section).queryAllByRole('textbox')).toHaveLength(0);
  });

  it('shows the startup state, not the runtime state, of a module switched in the settings UI', async () => {
    // chains runs but the configuration says off; webhooks is off but switched on here
    const fixture = modulesFixture('full', [], true);
    fixture.modules = fixture.modules.map((m) => {
      if (m.name === 'chains') return { ...m, enabled: true, configured: false, overridden: true };
      if (m.name === 'webhooks') return { ...m, enabled: false, configured: true, overridden: true };
      return m;
    });
    server.use(http.get('/api/v1/system/capabilities', () => HttpResponse.json(fixture)));
    renderWithProviders(<StartupConfigCard />);

    const section = await screen.findByTestId('startup-config');
    expect(await within(section).findByTestId('startup-config-module-chains')).toHaveTextContent('Off');
    expect(within(section).getByTestId('startup-config-module-webhooks')).toHaveTextContent('On');
  });

  it('shows the security hint only when ui-editable is on', async () => {
    serve([], true);
    renderWithProviders(<StartupConfigCard />);

    const section = await screen.findByTestId('startup-config');
    expect(await within(section).findByTestId('startup-config-ui-editable')).toHaveTextContent('On');
    expect(within(section).getByTestId('startup-config-ui-editable-risk')).toBeInTheDocument();

    // Still read-only even with ui-editable on
    const buttons = within(section).getAllByRole('button');
    expect(buttons).toHaveLength(1);
  });
});
