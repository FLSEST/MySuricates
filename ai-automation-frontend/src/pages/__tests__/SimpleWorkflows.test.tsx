import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Routes, Route, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import { useSettingsStore } from '../../stores/settingsStore';
import { SimpleWorkflows } from '../SimpleWorkflows';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
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

// Review 093d2f11: a blueprint package reached via ?template= goes through the server installation
describe('SimpleWorkflows — blueprint package', () => {
  it('installs it on the server instead of creating a workflow that names a blueprint', async () => {
    const installs: unknown[] = [];
    const creates: unknown[] = [];
    server.use(
      http.get('/api/v1/workflow-templates', () => HttpResponse.json([
        { id: 'blueprint-sub-workflow', name: { en: 'Calling a sub-workflow', de: 'Teil' }, description: { en: 'Package', de: 'Paket' },
          icon: '', category: 'BASICS', kind: 'BLUEPRINT', instructions: null, model: null,
          blocks: [{ type: 'sub-workflow', config: { workflow_id: 'blueprint-sub-workflow-part' } }] },
      ])),
      http.post('/api/v1/workflow-templates/:id/install', async ({ params, request }) => {
        installs.push([params.id, await request.json()]);
        return HttpResponse.json({ workflowId: 'w-parent', workflowName: 'Calling a sub-workflow', partWorkflowIds: ['w-part'] }, { status: 201 });
      }),
      http.post('/api/v1/workflows', async ({ request }) => {
        creates.push(await request.json());
        return HttpResponse.json({ id: 'w-plain' });
      }),
    );
    renderAt('/workflows?template=blueprint-sub-workflow');

    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByDisplayValue('Calling a sub-workflow');
    fireEvent.change(within(dialog).getByRole('combobox'), { target: { value: 'p1' } });
    fireEvent.click(within(dialog).getByRole('button', { name: en.common.create }));

    await waitFor(() => expect(installs).toHaveLength(1));
    expect(installs[0]).toEqual(['blueprint-sub-workflow', expect.objectContaining({ projectId: 'p1', locale: 'en' })]);
    expect(creates).toEqual([]);
  });
});

// 23635ef6: the app's own dialog instead of window.confirm
describe('SimpleWorkflows — delete', () => {
  it('names the workflow and deletes it only after confirmation', async () => {
    const deleted: string[] = [];
    server.use(http.delete('/api/v1/workflows/:id', ({ params }) => {
      deleted.push(String(params.id));
      return new HttpResponse(null, { status: 204 });
    }));
    renderAt('/workflows');

    await screen.findByText('Test Workflow');
    fireEvent.click(screen.getByRole('button', { name: en.common.delete }));
    const dialog = screen.getByRole('dialog', { name: 'Delete "Test Workflow"?' });
    expect(within(dialog).getByText(`${en.common.deleteConfirm} ${en.workflows.deleteConfirm}`)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: en.common.cancel }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(deleted).toEqual([]);

    fireEvent.click(screen.getByRole('button', { name: en.common.delete }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: en.common.delete }));
    await waitFor(() => expect(deleted).toEqual(['w1']));
  });
});
