import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, within, fireEvent } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import { useSettingsStore } from '../../stores/settingsStore';
import type { Project } from '../../types';
import { SimpleProjects } from '../SimpleProjects';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: true });
});

const project = (id: string, name: string, status: Project['status']) =>
  ({ id, name, type: 'CODE', path: `/tmp/${id}`, status, container: null, workflowCount: 2, config: {},
    createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }) as Project;

function card(name: string) {
  return screen.getByText(name).closest('button')!.parentElement as HTMLElement;
}

describe('SimpleProjects', () => {
  it('shows the container control only where a container is already running or starting', async () => {
    server.use(http.get('/api/v1/projects', () => HttpResponse.json([
      project('p1', 'Quiet', 'STOPPED'),
      project('p2', 'Busy', 'RUNNING'),
      project('p3', 'Waking', 'STARTING'),
    ])));

    renderWithProviders(<SimpleProjects />);
    await screen.findByText('Quiet');

    expect(within(card('Quiet')).queryByRole('button', { name: en.simple.projects.stopContainer })).toBeNull();
    expect(within(card('Quiet')).queryByText(en.simple.projects.containerStarting)).toBeNull();
    expect(within(card('Busy')).getByRole('button', { name: en.simple.projects.stopContainer })).toBeInTheDocument();
    expect(within(card('Waking')).getByRole('button', { name: en.simple.projects.containerStarting })).toBeDisabled();
    // The type reads as a word, not as the enum
    expect(within(card('Quiet')).getByText(new RegExp(en.projects.types.CODE))).toBeInTheDocument();
    expect(screen.queryByText(/^CODE ·/)).toBeNull();
  });

  it('stops a running container', async () => {
    let stopped: string | null = null;
    server.use(
      http.get('/api/v1/projects', () => HttpResponse.json([project('p2', 'Busy', 'RUNNING')])),
      http.post('/api/v1/projects/:id/stop', ({ params }) => {
        stopped = String(params.id);
        return HttpResponse.json(project('p2', 'Busy', 'STOPPED'));
      }),
    );

    renderWithProviders(<SimpleProjects />);
    fireEvent.click(await screen.findByRole('button', { name: en.simple.projects.stopContainer }));

    await waitFor(() => expect(stopped).toBe('p2'));
  });

  it('creates a project from a name and a folder, without asking for a type', async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/v1/projects', () => HttpResponse.json([project('p1', 'Quiet', 'STOPPED')])),
      http.post('/api/v1/projects', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(project('p9', 'Mine', 'STOPPED'));
      }),
    );

    renderWithProviders(<SimpleProjects />);
    await screen.findByText('Quiet');
    fireEvent.click(screen.getByRole('button', { name: en.common.create }));
    const dialog = await screen.findByRole('dialog');

    expect(within(dialog).queryByRole('combobox')).toBeNull();
    expect(within(dialog).getByText(en.projects.folder)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: en.projects.browse })).toBeInTheDocument();
    const create = within(dialog).getByRole('button', { name: en.common.create });
    expect(create).toBeDisabled();

    const [name, folder] = within(dialog).getAllByRole('textbox');
    fireEvent.change(name!, { target: { value: ' Mine ' } });
    fireEvent.change(folder!, { target: { value: '/home/me/mine' } });
    fireEvent.click(create);

    await waitFor(() => expect(body).toEqual({ name: 'Mine', type: 'PERSONAL', path: '/home/me/mine' }));
  });
});

describe('SimpleProjects — ?new=1 (the home field)', () => {
  it('opens the create dialog right away', async () => {
    server.use(http.get('/api/v1/projects', () => HttpResponse.json([project('p1', 'Quiet', 'STOPPED')])));
    renderWithProviders(<SimpleProjects />, { route: '/projects?new=1' });

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(en.simple.projects.createTitle)).toBeInTheDocument();
  });
});
