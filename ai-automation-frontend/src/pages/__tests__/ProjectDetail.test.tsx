import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { createElement } from 'react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { modulesFixture } from '../../test/modules';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import type { Project } from '../../types';

// jsdom can't run Monaco
vi.mock('../../components/shared/MonacoEditor', () => ({
  MonacoEditor: ({ value, onChange }: { value: string; onChange?: (v: string) => void }) =>
    createElement('textarea', {
      'data-testid': 'mock-monaco',
      value,
      onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => onChange?.(e.target.value),
    }),
}));

import { ProjectDetail } from '../ProjectDetail';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const project: Project = {
  id: 'p1', name: 'Alpha', subtitle: 'v1', description: 'First project', type: 'RESEARCH', path: '/tmp/alpha',
  config: {}, aiContext: 'Be brief.', status: 'STOPPED', container: null, workflowCount: 0,
  createdAt: '2026-09-01T10:00:00Z', updatedAt: '2026-09-02T10:00:00Z',
};

let calls: Array<{ method: string; path: string; body?: unknown }>;
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  // Without the onboarding module the page needs no onboarding endpoints
  const modules = modulesFixture('full', ['onboarding-profiles']);
  localStorage.setItem('mysuricates-modules', JSON.stringify(modules));
  calls = [];
  const record = (method: string) => async ({ request, params }: { request: Request; params: Record<string, unknown> }) => {
    const path = new URL(request.url).pathname;
    const body = method === 'PUT' ? await request.json() : undefined;
    calls.push({ method, path, body });
    if (path.endsWith('/generate-context')) return HttpResponse.json({ context: 'Generated context' });
    if (method === 'DELETE') return new HttpResponse(null, { status: 204 });
    return HttpResponse.json({ ...project, id: String(params.id) });
  };
  server.use(
    http.get('/api/v1/system/capabilities', () => HttpResponse.json(modules)),
    http.get('/api/v1/projects/:id', () => HttpResponse.json(project)),
    http.put('/api/v1/projects/:id', record('PUT')),
    http.post('/api/v1/projects/:id/start', record('POST')),
    http.post('/api/v1/projects/:id/generate-context', record('POST')),
    http.delete('/api/v1/projects/:id', record('DELETE')),
  );
});

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:id" element={<ProjectDetail />} />
      <Route path="/projects" element={<div>project list</div>} />
    </Routes>,
    { route: '/projects/p1' },
  );
}

describe('ProjectDetail', () => {
  it('edits the project with the shared form fields, prefilled', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: en.common.edit }));
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByLabelText(en.projects.name)).toHaveValue('Alpha');
    expect(dialog.getByLabelText(en.projects.subtitleField)).toHaveValue('v1');
    expect(dialog.getByDisplayValue('First project')).toBeInTheDocument();
    expect(dialog.getByRole('combobox')).toHaveValue('RESEARCH');

    fireEvent.change(dialog.getByLabelText(en.projects.name), { target: { value: 'Beta' } });
    fireEvent.change(dialog.getByRole('combobox'), { target: { value: 'CODE' } });
    fireEvent.click(dialog.getByRole('button', { name: en.common.save }));

    await waitFor(() => expect(calls).toContainEqual({ method: 'PUT', path: '/api/v1/projects/p1', body: {
      name: 'Beta', subtitle: 'v1', description: 'First project', type: 'CODE', path: '/tmp/alpha',
    } }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('edits, generates and saves the AI context', async () => {
    renderPage();
    expect(await screen.findByText('Be brief.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: en.projects.editAiContext }));
    const editor = screen.getByTestId('mock-monaco');
    expect(editor).toHaveValue('Be brief.');

    fireEvent.click(screen.getByRole('button', { name: en.projects.generateAiContext }));
    await waitFor(() => expect(screen.getByTestId('mock-monaco')).toHaveValue('Generated context'));
    fireEvent.change(screen.getByTestId('mock-monaco'), { target: { value: 'Generated context, edited' } });
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: en.common.save }));

    await waitFor(() => expect(calls).toContainEqual({
      method: 'PUT', path: '/api/v1/projects/p1', body: { aiContext: 'Generated context, edited' },
    }));
  });

  it('starts the container of a stopped project and archives after confirmation', async () => {
    renderPage();
    expect(await screen.findByText('No container')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: en.projects.startContainer }));
    await waitFor(() => expect(calls).toContainEqual({ method: 'POST', path: '/api/v1/projects/p1/start', body: undefined }));

    fireEvent.click(screen.getByRole('button', { name: en.projects.archive }));
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByText(en.projects.archiveConfirm)).toBeInTheDocument();
    fireEvent.click(dialog.getByRole('button', { name: en.projects.archive }));
    expect(await screen.findByText('project list')).toBeInTheDocument();
    expect(calls).toContainEqual({ method: 'DELETE', path: '/api/v1/projects/p1', body: undefined });
  });
});
