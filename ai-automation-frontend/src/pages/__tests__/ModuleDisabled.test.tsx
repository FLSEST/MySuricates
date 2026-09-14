import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { modulesFixture } from '../../test/modules';
import { useI18nStore } from '../../i18n';
import { ModuleDisabled } from '../ModuleDisabled';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

describe('ModuleDisabled', () => {
  it('explains the disabled module and shows how to enable it', async () => {
    server.use(
      http.get('/api/v1/system/capabilities', () => HttpResponse.json(modulesFixture('core', ['remote-access']))),
    );

    renderWithProviders(<ModuleDisabled module="remote-access" />);

    expect(screen.getByText('Module "Remote access" is disabled')).toBeInTheDocument();
    expect(screen.getByText(/remote-access: true/)).toBeInTheDocument();
    expect(await screen.findByText('AUTOMATION_MODULES_REMOTE_ACCESS=true')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'All modules' })).toHaveAttribute('href', '/settings#modules');
  });

  it('points to the settings switch when modules are UI-editable', async () => {
    server.use(
      http.get('/api/v1/system/capabilities', () => HttpResponse.json(modulesFixture('core', ['webhooks'], true))),
    );

    renderWithProviders(<ModuleDisabled module="webhooks" />);

    expect(await screen.findByTestId('module-disabled-ui-hint')).toHaveTextContent('Settings → Modules & hardening');
  });
});
