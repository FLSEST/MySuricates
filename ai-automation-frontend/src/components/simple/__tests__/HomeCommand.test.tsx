import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Routes, Route, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import { de } from '../../../i18n/de';
import hintsEn from '../../../data/hints.en.json';
import { useSettingsStore } from '../../../stores/settingsStore';
import { useThemeStore } from '../../../stores/themeStore';
import type { ModuleName, Project, Workflow, WorkflowTemplate } from '../../../types';
import type { PendingInput } from '../../../hooks/usePendingInputs';
import { HomeCommand } from '../home-command/HomeCommand';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: true, hintsEnabled: true, dismissedHints: {} });
});

const c = en.simple.home.command;
const ago = (hours: number) => new Date(Date.now() - hours * 3_600_000).toISOString();

const workflow = (id: string, name: string, description = '') =>
  ({ id, projectId: 'p1', projectName: 'Mine', name, description, icon: '', blocks: [], version: '1', metadata: {},
    runCount: 1, tags: [], createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }) as unknown as Workflow;

const run = (id: string, workflowId: string, workflowName: string, status: string, startedAt: string, message?: string) =>
  ({ id, workflowId, workflowName, status, startedAt, completedAt: null, durationMs: 1000, error: message ? { message } : null,
    projectName: null, projectPath: null, userRating: null, metadata: {}, stepCount: 1, lastStepStatus: null, lastStepPreview: null });

const template = (id: string, name: string) =>
  ({ id, name: { en: name, de: name }, description: { en: 'A finished flow', de: 'Ein fertiger Ablauf' }, icon: '', category: null,
    blocks: [], instructions: null, model: null, builtIn: true, sortOrder: 0, qualityStatus: 'TESTED', verifications: [],
    version: '1', createdAt: '', updatedAt: '', kind: 'TEMPLATE' }) as WorkflowTemplate;

const approval = (runId: string, workflowName: string): PendingInput => ({
  runId, workflowId: 'w1', workflowName, type: 'approval', blockId: 'b1', question: 'Send the **invoice**?',
  options: ['Send', 'Stop'], rejectOptions: ['Stop'], stepIndex: 0, requestedAt: ago(1), timeoutAt: null,
});

interface Setup {
  disabled?: ModuleName[];
  workflows?: Workflow[];
  runs?: ReturnType<typeof run>[];
  pending?: PendingInput[];
  templates?: WorkflowTemplate[];
  projects?: Project[];
}

const requests: string[] = [];

function serve({
  disabled = [], workflows = [workflow('w1', 'Morning digest', 'News summary'), workflow('w2', 'Backup photos')],
  runs = [], pending = [], templates = [template('t1', 'Invoice reminder')], projects = [],
}: Setup = {}) {
  requests.length = 0;
  server.use(
    http.get('/api/v1/system/capabilities', () => {
      requests.push('capabilities');
      return HttpResponse.json({ routes: [], blockTypes: [], ...modulesFixture('full', disabled) });
    }),
    http.get('/api/v1/workflows', () => HttpResponse.json(workflows)),
    http.get('/api/v1/projects', () => HttpResponse.json(projects)),
    http.get('/api/v1/executions', () => HttpResponse.json({ content: runs, page: 0, size: 100, totalElements: runs.length, totalPages: 1 })),
    http.get('/api/v1/executions/pending-input', () => HttpResponse.json(pending)),
    http.get('/api/v1/workflow-templates', () => { requests.push('templates'); return HttpResponse.json(templates); }),
    http.get('/api/v1/pool', () => { requests.push('pool'); return HttpResponse.json([]); }),
    http.get('/api/v1/schedules', () => { requests.push('schedules'); return HttpResponse.json([]); }),
    http.get('/api/v1/todos', ({ request }) => {
      requests.push(`todos:${new URL(request.url).searchParams.get('q')}`);
      return HttpResponse.json({ content: [], page: 0, size: 3, totalElements: 0, totalPages: 0 });
    }),
  );
}

function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location">{location.pathname + location.search}</p>;
}

function renderCommand(onAsk?: (text: string) => void) {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/" element={<HomeCommand onAsk={onAsk} />} />
        <Route path="*" element={null} />
      </Routes>
      <LocationProbe />
    </>,
  );
}

const field = () => screen.getByRole('combobox', { name: c.label });
const type = (value: string) => fireEvent.change(field(), { target: { value } });
const group = (name: string) => screen.findByRole('group', { name });
const location = () => screen.getByTestId('location');

describe('HomeCommand — hits while typing', () => {
  it('shows nothing and fetches nothing extra while the field is empty', async () => {
    serve();
    renderCommand();
    await new Promise((r) => setTimeout(r, 300));
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(requests.filter((r) => r !== 'capabilities')).toEqual([]);
  });

  it('starts a matching workflow from its row', async () => {
    serve();
    let started: string | null = null;
    server.use(http.post('/api/v1/workflows/:id/execute', ({ params }) => {
      started = String(params.id);
      return HttpResponse.json({ id: 'run-1', workflowId: params.id, status: 'RUNNING' });
    }));
    renderCommand();

    type('backup');
    const workflows = await group(c.groups.workflows);
    const row = within(workflows).getByRole('option', { name: /Backup photos/ });
    expect(within(workflows).queryByRole('option', { name: /Morning digest/ })).toBeNull();
    fireEvent.click(within(row).getByRole('button', { name: c.start }));

    await waitFor(() => expect(started).toBe('w2'));
    await waitFor(() => expect(location()).toHaveTextContent('/workflows/w2/run/run-1'));
  });

  it('offers the latest result of a workflow', async () => {
    serve({ runs: [run('r9', 'w1', 'Morning digest', 'COMPLETED', ago(2))] });
    renderCommand();

    type('digest');
    const workflows = await group(c.groups.workflows);
    fireEvent.click(await within(workflows).findByRole('button', { name: c.latestResult }));
    expect(location()).toHaveTextContent('/workflows/w1/run/r9');
  });

  it('opens the create flow with a matching template chosen', async () => {
    serve();
    renderCommand();

    type('invoice');
    const templates = await group(c.groups.templates);
    const row = within(templates).getByRole('option', { name: /Invoice reminder/ });
    fireEvent.click(within(row).getByRole('button', { name: c.create }));
    expect(location()).toHaveTextContent('/workflows?template=t1');
    expect(requests).toContain('templates');
  });

  it('lists failed runs on a failure word and retries one with its input', async () => {
    serve({
      runs: [
        run('r1', 'w2', 'Backup photos', 'FAILED', ago(1), 'disk full'),
        run('r2', 'w1', 'Morning digest', 'COMPLETED', ago(2)),
      ],
    });
    let retried: string | null = null;
    server.use(http.post('/api/v1/executions/:id/retry', ({ params }) => {
      retried = String(params.id);
      return HttpResponse.json({ id: 'r3', workflowId: 'w2', status: 'RUNNING' });
    }));
    renderCommand();

    type('what failed?');
    const runs = await group(c.groups.runs);
    // The failure intent lifts the results to the top
    expect(screen.getAllByRole('group')[0]).toBe(runs);
    const row = within(runs).getByRole('option', { name: /Backup photos/ });
    expect(row).toHaveTextContent('disk full');
    expect(within(runs).queryByRole('option', { name: /Morning digest/ })).toBeNull();

    fireEvent.click(within(row).getByRole('button', { name: en.simple.runs.retry }));
    await waitFor(() => expect(retried).toBe('r1'));
    await waitFor(() => expect(location()).toHaveTextContent('/workflows/w2/run/r3'));
  });

  it('answers an approval right in the row', async () => {
    serve({ pending: [approval('r7', 'Invoice run')] });
    let body: unknown = null;
    let answeredRun: string | null = null;
    server.use(http.post('/api/v1/executions/:id/approve', async ({ params, request }) => {
      answeredRun = String(params.id);
      body = await request.json();
      return HttpResponse.json({});
    }));
    renderCommand();

    type('waiting');
    const approvals = await group(c.groups.approvals);
    const row = within(approvals).getByRole('option', { name: /Invoice run/ });
    expect(row).toHaveTextContent('Send the invoice?');
    fireEvent.click(within(row).getByRole('button', { name: 'Send' }));

    await waitFor(() => expect(answeredRun).toBe('r7'));
    expect(body).toEqual({ answer: 'Send' });
  });

  it('adds a task from the "task:" prefix with Enter', async () => {
    serve();
    let created: unknown = null;
    server.use(http.post('/api/v1/todos', async ({ request }) => {
      created = await request.json();
      return HttpResponse.json({ id: 'td1', title: 'Pay taxes' });
    }));
    renderCommand(() => { throw new Error('must not ask'); });

    type('task: Pay taxes');
    const todos = await group(c.groups.todos);
    expect(within(todos).getByRole('option', { name: /Pay taxes/ })).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(field(), { key: 'Enter' });

    await waitFor(() => expect(created).toEqual({ title: 'Pay taxes' }));
    await waitFor(() => expect(field()).toHaveValue(''));
    expect(requests.some((r) => r.startsWith('todos:'))).toBe(false);
  });

  it('searches open tasks on the server with one word', async () => {
    serve();
    renderCommand();
    type('Rechnung prüfen');
    await waitFor(() => expect(requests).toContain('todos:Rechnung'));
  });

  it('expands an explanation in place', async () => {
    serve();
    renderCommand();

    type('what is a project?');
    const explanations = await group(c.groups.explanations);
    const row = within(explanations).getByRole('option', { name: new RegExp(hintsEn['projects-directory'].title) });
    expect(row).not.toHaveTextContent(hintsEn['projects-directory'].points[0]!);
    fireEvent.click(within(row).getByRole('button', { name: c.show }));
    expect(row).toHaveTextContent(hintsEn['projects-directory'].points[0]!);
    expect(row).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(within(row).getByRole('button', { name: c.hide }));
    expect(row).not.toHaveTextContent(hintsEn['projects-directory'].points[0]!);
  });

  it('switches the language from an action', async () => {
    serve();
    renderCommand();

    type('deutsch');
    const actions = await group(c.groups.actions);
    const row = within(actions).getByRole('option', { name: /Deutsch/ });
    fireEvent.click(within(row).getByRole('button', { name: c.apply }));
    expect(useI18nStore.getState().locale).toBe('de');
    // Done: the query is cleared, the placeholder speaks German now
    expect(screen.getByRole('combobox', { name: de.simple.home.command.label })).toHaveValue('');
  });

  it('switches light and dark from an action', async () => {
    serve();
    const before = useThemeStore.getState().themeId;
    renderCommand();

    type('dark');
    const actions = await group(c.groups.actions);
    fireEvent.click(within(actions).getAllByRole('button', { name: c.apply })[0]!);
    expect(useThemeStore.getState().themeId).not.toBe(before);
  });

  it('explains module pages only while the module is on', async () => {
    serve();
    renderCommand();
    type('describe a goal');
    expect(await screen.findByRole('option', { name: new RegExp(hintsEn['wishes-loop'].title) })).toBeInTheDocument();
  });

  it('leaves out what a disabled module would bring', async () => {
    serve({ disabled: ['wishes'] });
    renderCommand();
    // Module state known before typing (the capabilities are cached app-wide)
    await waitFor(() => expect(requests).toContain('capabilities'));
    await new Promise((r) => setTimeout(r, 50));
    type('describe a goal');
    await screen.findByRole('listbox');
    await new Promise((r) => setTimeout(r, 300));
    expect(screen.queryByRole('option', { name: new RegExp(hintsEn['wishes-loop'].title) })).toBeNull();
  });

  it('offers all workflows and a template when nothing is found', async () => {
    serve();
    renderCommand();

    type('xyzzy');
    expect(await screen.findByText(en.simple.home.noMatch)).toBeInTheDocument();
    // Only the generated "add as task" row — never highlighted
    expect(screen.getByRole('option', { name: /xyzzy/ })).toHaveAttribute('aria-selected', 'false');
    fireEvent.click(screen.getByRole('button', { name: en.simple.home.startFromTemplate }));
    expect(location()).toHaveTextContent('/workflows?new=1');
  });
});

describe('HomeCommand — keyboard', () => {
  it('moves the highlight with the arrow keys and activates it with Enter', async () => {
    serve({ runs: [run('r1', 'w2', 'Backup photos', 'COMPLETED', ago(1))] });
    renderCommand(() => undefined);

    type('backup');
    const workflows = await group(c.groups.workflows);
    const results = await group(c.groups.runs);
    const first = within(workflows).getByRole('option', { name: /Backup photos/ });
    const second = within(results).getByRole('option', { name: /Backup photos/ });

    // With the assistant nothing is highlighted until the arrows are used
    expect(field()).not.toHaveAttribute('aria-activedescendant');
    fireEvent.keyDown(field(), { key: 'ArrowDown' });
    expect(field()).toHaveAttribute('aria-activedescendant', first.id);
    expect(first).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(field(), { key: 'ArrowDown' });
    expect(field()).toHaveAttribute('aria-activedescendant', second.id);
    fireEvent.keyDown(field(), { key: 'ArrowUp' });
    fireEvent.keyDown(field(), { key: 'ArrowUp' });
    expect(field()).not.toHaveAttribute('aria-activedescendant');

    fireEvent.keyDown(field(), { key: 'ArrowDown' });
    fireEvent.keyDown(field(), { key: 'ArrowDown' });
    fireEvent.keyDown(field(), { key: 'Enter' });
    expect(location()).toHaveTextContent('/workflows/w2/run/r1');
  });

  it('clears the field with Escape', async () => {
    serve();
    renderCommand();
    type('backup');
    await group(c.groups.workflows);
    fireEvent.keyDown(field(), { key: 'Escape' });
    expect(field()).toHaveValue('');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('without the assistant, Enter activates the first hit', async () => {
    serve();
    let started: string | null = null;
    server.use(http.post('/api/v1/workflows/:id/execute', ({ params }) => {
      started = String(params.id);
      return HttpResponse.json({ id: 'run-2', workflowId: params.id, status: 'RUNNING' });
    }));
    renderCommand();

    type('digest');
    const workflows = await group(c.groups.workflows);
    expect(within(workflows).getByRole('option', { name: /Morning digest/ })).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(field(), { key: 'Enter' });
    await waitFor(() => expect(started).toBe('w1'));
  });

  it('with the assistant, Enter without a highlight asks it', async () => {
    serve();
    const asked: string[] = [];
    renderCommand((text) => asked.push(text));

    type('digest');
    await group(c.groups.workflows);
    expect(screen.getByText(c.askHint)).toBeInTheDocument();
    fireEvent.keyDown(field(), { key: 'Enter' });
    expect(asked).toEqual(['digest']);
    // The text and its hits stay: the answer shows above them
    expect(field()).toHaveValue('digest');
    expect(await group(c.groups.workflows)).toBeInTheDocument();
  });

  it('does not ask with an empty field', async () => {
    serve();
    const asked: string[] = [];
    renderCommand((text) => asked.push(text));

    fireEvent.submit(screen.getByRole('search'));
    expect(asked).toEqual([]);
  });
});
