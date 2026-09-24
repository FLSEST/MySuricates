import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import { Routes, Route, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import { useSettingsStore } from '../../stores/settingsStore';
import { SimpleWorkflows } from '../SimpleWorkflows';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: true });
});

function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location">{location.pathname + location.search}</p>;
}

function renderAt(route: string) {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/workflows" element={<SimpleWorkflows />} />
      </Routes>
      <LocationProbe />
    </>,
    { route },
  );
}

describe('SimpleWorkflows — ?new=1', () => {
  it('opens the create dialog and drops the param', async () => {
    renderAt('/workflows?new=1');

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(en.workflows.create)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/workflows$/));
    // Still open after the param went
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('keeps the dialog closed without the param', async () => {
    renderAt('/workflows');

    await screen.findByText('Test Workflow');
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('SimpleWorkflows — ?template= and ?pool= (hits of the home field)', () => {
  const localized = (text: string) => ({ en: text, de: text });

  it('opens the create dialog with the template chosen', async () => {
    server.use(http.get('/api/v1/workflow-templates', () => HttpResponse.json([
      { id: 't1', name: localized('Invoice reminder'), description: localized('Reminds of open invoices'), icon: '',
        category: null, blocks: [], instructions: null, model: null, kind: 'TEMPLATE' },
    ])));
    renderAt('/workflows?template=t1');

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByDisplayValue('Invoice reminder')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/workflows$/));
  });

  it('opens the create dialog with the pool entry chosen', async () => {
    server.use(http.get('/api/v1/pool', () => HttpResponse.json([
      { id: 'pool-1', name: localized('Photo backup'), description: localized('Copies photos'), icon: '', category: null,
        scope: null, tags: [], blocks: [], instructions: null, model: null, version: '1', author: null, defaultContext: null,
        filename: 'photo.json' },
    ])));
    renderAt('/workflows?pool=pool-1');

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByDisplayValue('Photo backup')).toBeInTheDocument();
  });

  it('keeps the template list open for an unknown template', async () => {
    server.use(http.get('/api/v1/workflow-templates', () => HttpResponse.json([])));
    renderAt('/workflows?template=gone');

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(en.workflows.create)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/workflows$/));
    expect(within(screen.getByRole('dialog')).queryByDisplayValue('Invoice reminder')).toBeNull();
  });
});
