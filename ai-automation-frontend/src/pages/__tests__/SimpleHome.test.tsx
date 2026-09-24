import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Routes, Route, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { modulesFixture } from '../../test/modules';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import hintsEn from '../../data/hints.en.json';
import { useSettingsStore } from '../../stores/settingsStore';
import { useAssistantStore } from '../../stores/assistantStore';
import type { ModuleName, Project, Workflow } from '../../types';
import type { PendingInput } from '../../hooks/usePendingInputs';
import { SimpleHome } from '../SimpleHome';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: true, hintsEnabled: true, dismissedHints: {}, pinnedWorkflowIds: [] });
});

const project = (id: string, name: string) =>
  ({ id, name, type: 'CODE', path: `/tmp/${id}`, status: 'STOPPED', container: null, workflowCount: 1, config: {},
    createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }) as Project;

const workflow = (id: string, name: string, description = '') =>
  ({ id, projectId: 'p1', projectName: 'Mine', name, description, icon: '📰', blocks: [], version: '1', metadata: {},
    runCount: 1, tags: [], createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }) as unknown as Workflow;

const waiting = (runId: string): PendingInput => ({
  runId, workflowId: 'w1', workflowName: 'Digest', type: 'approval', blockId: 'b1', question: 'Go?',
  options: ['Yes', 'No'], rejectOptions: ['No'], stepIndex: 0, requestedAt: '2026-01-01T00:00:00Z', timeoutAt: null,
});

function serve({
  disabled = [] as ModuleName[],
  projects = [project('p1', 'Mine')],
  workflows = [workflow('w1', 'Morning digest', 'News summary'), workflow('w2', 'Backup photos')],
  pending = [] as PendingInput[],
} = {}) {
  server.use(
    http.get('/api/v1/system/capabilities', () => HttpResponse.json({ routes: [], blockTypes: [], ...modulesFixture('core', disabled) })),
    http.get('/api/v1/projects', () => HttpResponse.json(projects)),
    http.get('/api/v1/workflows', () => HttpResponse.json(workflows)),
    http.get('/api/v1/executions', () => HttpResponse.json({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })),
    http.get('/api/v1/executions/pending-input', () => HttpResponse.json(pending)),
  );
}

function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location">{location.pathname + location.search}</p>;
}

function renderHome() {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/" element={<SimpleHome />} />
        <Route path="*" element={null} />
      </Routes>
      <LocationProbe />
    </>,
  );
}

const command = en.simple.home.command;
const searchField = () => screen.findByRole('combobox', { name: command.label });
const workflowHits = () => screen.findByRole('group', { name: command.groups.workflows });

describe('SimpleHome — without the assistant module', () => {
  it('searches the own workflows instead of offering the assistant', async () => {
    serve({ disabled: ['assistant'] });
    renderHome();

    const input = await searchField();
    await waitFor(() => expect(screen.queryByRole('button', { name: en.simple.home.assistantLabel })).toBeNull());

    fireEvent.change(input, { target: { value: 'digest' } });
    const matches = await workflowHits();
    expect(within(matches).getByRole('option', { name: /Morning digest/ })).toBeInTheDocument();
    expect(within(matches).queryByRole('option', { name: /Backup photos/ })).toBeNull();
  });

  it('starts a matching workflow right away', async () => {
    serve({ disabled: ['assistant'] });
    let started: string | null = null;
    server.use(http.post('/api/v1/workflows/:id/execute', ({ params }) => {
      started = String(params.id);
      return HttpResponse.json({ id: 'run-1', workflowId: params.id, status: 'RUNNING' });
    }));
    renderHome();

    fireEvent.change(await searchField(), { target: { value: 'backup' } });
    const row = within(await workflowHits()).getByRole('option', { name: /Backup photos/ });
    fireEvent.click(within(row).getByRole('button', { name: command.start }));

    await waitFor(() => expect(started).toBe('w2'));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/workflows/w2/run/run-1'));
  });

  it('offers all workflows and a template when nothing matches', async () => {
    serve({ disabled: ['assistant'] });
    renderHome();

    fireEvent.change(await searchField(), { target: { value: 'xyzzy' } });
    expect(await screen.findByText(en.simple.home.noMatch)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.simple.home.allWorkflows })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: en.simple.home.startFromTemplate }));
    expect(screen.getByTestId('location')).toHaveTextContent('/workflows?new=1');
  });
});

describe('SimpleHome — with the assistant module', () => {
  it('keeps the assistant in the hero field', async () => {
    serve();
    renderHome();

    expect(await screen.findByRole('button', { name: en.simple.home.assistantLabel })).toBeInTheDocument();
    await screen.findByText(en.simple.home.runTilesTitle);
    expect(await searchField()).toBeInTheDocument();
  });

  it('shows instant hits and hands the text to the assistant on Enter', async () => {
    useAssistantStore.setState({ open: false, pendingInput: null });
    serve();
    renderHome();

    const input = await searchField();
    fireEvent.change(input, { target: { value: 'digest' } });
    expect(within(await workflowHits()).getByRole('option', { name: /Morning digest/ })).toBeInTheDocument();

    fireEvent.keyDown(input, { key: 'Enter' });
    expect(useAssistantStore.getState()).toMatchObject({ open: true, pendingInput: 'digest' });
  });
});

describe('SimpleHome — first start', () => {
  it('offers the example project while there is no project', async () => {
    serve({ disabled: ['assistant'], projects: [], workflows: [] });
    renderHome();

    expect(await screen.findByRole('button', { name: en.projects.firstProject.createExample })).toBeInTheDocument();
    expect(screen.getByText(en.projects.firstProject.title)).toBeInTheDocument();
    expect(screen.queryByText(en.simple.home.firstWorkflowTitle)).toBeNull();
    // The field is there from the start: templates, actions and explanations need no workflow
    expect(await searchField()).toBeInTheDocument();
  });

  it('offers a template once there is a project but no workflow', async () => {
    serve({ disabled: ['assistant'], workflows: [] });
    renderHome();

    expect(await screen.findByText(en.simple.home.firstWorkflowTitle)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: en.projects.firstProject.createExample })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: en.simple.home.startFromTemplate }));
    expect(screen.getByTestId('location')).toHaveTextContent('/workflows?new=1');
  });

  it('shows neither offer once workflows exist', async () => {
    serve({ disabled: ['assistant'] });
    renderHome();

    await searchField();
    expect(screen.queryByText(en.simple.home.firstWorkflowTitle)).toBeNull();
    expect(screen.queryByText(en.projects.firstProject.title)).toBeNull();
  });
});

describe('SimpleHome — hint and waiting answers', () => {
  it('explains Simple mode with a hint in place', async () => {
    serve();
    renderHome();

    expect(await screen.findByText(hintsEn['simple-home'].title)).toBeInTheDocument();
  });

  it('shows what waits for an answer, and leads to the approvals', async () => {
    serve({ pending: [waiting('r1'), waiting('r2')] });
    renderHome();

    const pill = await screen.findByRole('button', { name: new RegExp(en.simple.home.waitingLabel) });
    expect(pill).toHaveTextContent('2');
    fireEvent.click(pill);
    expect(screen.getByTestId('location')).toHaveTextContent('/approvals');
  });

  it('shows no waiting pill while nothing waits', async () => {
    serve();
    renderHome();

    await screen.findByRole('button', { name: new RegExp(`${en.simple.home.ranLabel}$`) });
    expect(screen.queryByText(en.simple.home.waitingLabel)).toBeNull();
  });
});
