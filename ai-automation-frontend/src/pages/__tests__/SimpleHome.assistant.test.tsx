import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Routes, Route, useLocation } from 'react-router-dom';
import { http, HttpResponse, delay } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { modulesFixture } from '../../test/modules';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import { useSettingsStore } from '../../stores/settingsStore';
import { useAssistantStore } from '../../stores/assistantStore';
import { resetAssistantConversation } from '../../hooks/useAssistantConversation';
import type { AssistantChatRequest, AssistantChatResponse } from '../../hooks/useAssistant';
import type { ModuleName, Project, Workflow } from '../../types';
import { SimpleHome } from '../SimpleHome';

// No live connection in the test: the answer card only needs the hook's shape.
vi.mock('../../hooks/useAssistantWebSocket', () => ({
  useAssistantWebSocket: () => ({ currentPhase: null, events: [] }),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => { server.resetHandlers(); resetAssistantConversation(); });
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: true, hintsEnabled: true, dismissedHints: {}, pinnedWorkflowIds: [], defaultAiProvider: '' });
  useAssistantStore.setState({ open: false, sessions: [], activeSessionId: null, pendingInput: null, inFlight: null });
});

const project = { id: 'p1', name: 'Mine', type: 'CODE', path: '/tmp/p1', status: 'STOPPED', container: null, workflowCount: 2,
  config: {}, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' } as Project;

const workflow = (id: string, name: string, prompt = 'Summarise the news') =>
  ({ id, projectId: 'p1', projectName: 'Mine', name, description: '', icon: '📰', version: '1', metadata: {},
    blocks: [{ id: 'b1', type: 'simple', name: 'Step', config: { prompt } }],
    runCount: 1, tags: [], createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }) as unknown as Workflow;

const reply = (message: string, extra: Partial<AssistantChatResponse> = {}): AssistantChatResponse =>
  ({ message, actions: [], model: 'sonnet', ...extra });

const chats: AssistantChatRequest[] = [];

function serve({ disabled = [] as ModuleName[], answers = [] as (AssistantChatResponse | 'fail' | 'slow')[] } = {}) {
  chats.length = 0;
  server.use(
    http.get('/api/v1/system/capabilities', () => HttpResponse.json({ routes: [], blockTypes: [], ...modulesFixture('full', disabled) })),
    http.get('/api/v1/projects', () => HttpResponse.json([project])),
    http.get('/api/v1/workflows', () => HttpResponse.json([
      workflow('w1', 'Morning digest'),
      workflow('w2', 'Backup photos'),
      workflow('w3', 'Research topic', 'Research {{ topic }}'),
    ])),
    http.get('/api/v1/executions', () => HttpResponse.json({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })),
    http.get('/api/v1/executions/pending-input', () => HttpResponse.json([])),
    http.post('/api/v1/assistant/chat', async ({ request }) => {
      chats.push((await request.json()) as AssistantChatRequest);
      const next = answers.shift() ?? reply('…');
      if (next === 'fail') return HttpResponse.json({ error: 'boom' }, { status: 500 });
      if (next === 'slow') { await delay(10_000); return HttpResponse.json(reply('late')); }
      return HttpResponse.json(next);
    }),
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
const a = command.answer;
const searchField = () => screen.findByRole('combobox', { name: command.label });
const answerCard = () => screen.findByRole('region', { name: a.label });

async function askFor(text: string) {
  const input = await searchField();
  // The assistant module must be known to be on before Enter asks
  await screen.findByRole('button', { name: en.simple.home.assistantLabel });
  fireEvent.change(input, { target: { value: text } });
  fireEvent.keyDown(input, { key: 'Enter' });
  return input;
}

// Renders the whole home page (sources, overview, tiles) — give it room on a loaded machine
describe('SimpleHome — the assistant answers in place', { timeout: 15_000 }, () => {
  it('shows the answer above the hits, which stay for the same text', async () => {
    serve({ answers: [reply('The **Morning digest** ran at 7:00.')] });
    renderHome();

    const input = await askFor('digest');
    const card = await answerCard();
    expect(within(card).getByText('digest')).toBeInTheDocument();

    expect(await within(card).findByText('Morning digest', { selector: 'strong' })).toBeInTheDocument();
    expect(input).toHaveValue('digest');
    const hits = await screen.findByRole('group', { name: command.groups.workflows });
    expect(within(hits).getByRole('option', { name: /Morning digest/ })).toBeInTheDocument();
    // The answer comes first
    expect(card.compareDocumentPosition(hits) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    expect(chats[0]).toMatchObject({ message: 'digest', history: [], language: 'en', context: { page: 'dashboard', pathname: '/' } });
    // The exchange is in an assistant session, so the panel shows it too
    const session = useAssistantStore.getState().sessions[0]!;
    expect(chats[0]!.sessionId).toBe(session.id);
    expect(session.messages.map((m) => m.role)).toEqual(['user', 'assistant']);
  });

  it('shows a status while the assistant works and cancels on request', async () => {
    serve({ answers: ['slow'] });
    renderHome();

    await askFor('slow question');
    const card = await answerCard();
    expect(within(card).getByRole('status')).toHaveTextContent(en.assistant.phaseThinking);

    fireEvent.click(within(card).getByRole('button', { name: en.common.cancel }));
    expect(await within(card).findByText(a.cancelled)).toBeInTheDocument();
    expect(useAssistantStore.getState().inFlight).toBeNull();
  });

  it('offers a retry after an error', async () => {
    serve({ answers: ['fail', reply('Second time lucky')] });
    renderHome();

    await askFor('flaky');
    const card = await answerCard();
    expect(await within(card).findByRole('alert')).toHaveTextContent(a.error);

    fireEvent.click(within(card).getByRole('button', { name: a.retry }));
    expect(await within(card).findByText('Second time lucky')).toBeInTheDocument();
    expect(chats.map((c) => c.message)).toEqual(['flaky', 'flaky']);
  });

  it('starts a workflow from a run action right away', async () => {
    serve({ answers: [reply('Start it?', { actions: [{ type: 'run_workflow', id: 'w2', name: 'Backup photos', label: 'Run Backup photos' }] })] });
    let started: string | null = null;
    server.use(http.post('/api/v1/workflows/:id/execute', ({ params }) => {
      started = String(params.id);
      return HttpResponse.json({ id: 'run-7', workflowId: params.id, status: 'RUNNING' });
    }));
    renderHome();

    await askFor('backup now');
    const card = await answerCard();
    fireEvent.click(await within(card).findByRole('button', { name: 'Run Backup photos' }));

    await waitFor(() => expect(started).toBe('w2'));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/workflows/w2/run/run-7'));
  });

  it('asks for a workflow\'s inputs before starting it from an action', async () => {
    serve({ answers: [reply('Research it?', { actions: [{ type: 'run_workflow', id: 'w3', name: 'Research topic', label: 'Run research' }] })] });
    renderHome();

    await askFor('research');
    const card = await answerCard();
    fireEvent.click(await within(card).findByRole('button', { name: 'Run research' }));

    expect(await screen.findByText(en.simple.run.inputsTitle)).toBeInTheDocument();
  });

  it('opens a run from a show action', async () => {
    serve({ answers: [reply('Here it is', { actions: [{ type: 'show_run', id: 'r9', name: 'Run', label: 'Open the run' }] })] });
    renderHome();

    await askFor('last run');
    fireEvent.click(await within(await answerCard()).findByRole('button', { name: 'Open the run' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/runs/r9');
  });

  it('sends a chosen option as the next message of the same conversation', async () => {
    serve({ answers: [
      reply('Which one?', { actions: [{ type: 'ask_choice', id: null, name: 'pick', label: 'Pick', options: ['Morning digest', 'Backup photos'] }] }),
      reply('Backup photos ran fine.'),
    ] });
    renderHome();

    await askFor('did it run');
    const card = await answerCard();
    fireEvent.click(await within(card).findByRole('button', { name: 'Backup photos' }));

    expect(await within(card).findByText('Backup photos ran fine.')).toBeInTheDocument();
    expect(chats).toHaveLength(2);
    expect(chats[1]!.sessionId).toBe(chats[0]!.sessionId);
    expect(chats[1]!.history).toEqual([
      { role: 'user', content: 'did it run' },
      { role: 'assistant', content: 'Which one?' },
    ]);
    expect(useAssistantStore.getState().sessions).toHaveLength(1);
  });

  it('confirms a proposed change in place', async () => {
    serve({ answers: [reply('I can create it.', {
      pendingTools: [{ id: 'tool-1', type: 'create_todo', description: 'Create task “Call Anna”', expiresAt: new Date(Date.now() + 600_000).toISOString() }],
    })] });
    let confirmed: { id: string; body: unknown } | null = null;
    server.use(http.post('/api/v1/assistant/pending-tools/:id/confirm', async ({ params, request }) => {
      confirmed = { id: String(params.id), body: await request.json() };
      return HttpResponse.json({ toolId: 'tool-1', type: 'create_todo', success: true, message: 'Task created' });
    }));
    renderHome();

    await askFor('remind me to call Anna');
    const card = await answerCard();
    expect(await within(card).findByText('Create task “Call Anna”')).toBeInTheDocument();
    fireEvent.click(within(card).getByRole('button', { name: en.assistant.pendingToolConfirm }));

    expect(await within(card).findByText('Task created')).toBeInTheDocument();
    expect(confirmed).toEqual({ id: 'tool-1', body: { sessionId: chats[0]!.sessionId } });
  });

  it('continues in the chat panel with the same conversation', async () => {
    serve({ answers: [reply('Sure.')] });
    renderHome();

    await askFor('hello');
    const card = await answerCard();
    await within(card).findByText('Sure.');
    fireEvent.click(within(card).getByRole('button', { name: a.continueInChat }));

    expect(useAssistantStore.getState()).toMatchObject({ open: true, activeSessionId: chats[0]!.sessionId, pendingInput: null });
  });

  it('keeps the answer while typing on, and Escape clears field and answer', async () => {
    serve({ answers: [reply('Sure.')] });
    renderHome();

    const input = await askFor('hello');
    await within(await answerCard()).findByText('Sure.');
    fireEvent.change(input, { target: { value: 'something else' } });
    expect(screen.getByRole('region', { name: a.label })).toBeInTheDocument();

    fireEvent.keyDown(input, { key: 'Escape' });
    expect(input).toHaveValue('');
    expect(screen.queryByRole('region', { name: a.label })).toBeNull();
  });

  it('closes the answer with its × and starts a new conversation with the next question', async () => {
    serve({ answers: [reply('First.'), reply('Second.')] });
    renderHome();

    const input = await askFor('one');
    const card = await answerCard();
    await within(card).findByText('First.');
    fireEvent.click(within(card).getByRole('button', { name: a.close }));
    expect(screen.queryByRole('region', { name: a.label })).toBeNull();
    expect(input).toHaveValue('one');

    fireEvent.change(input, { target: { value: 'two' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(await within(await answerCard()).findByText('Second.')).toBeInTheDocument();
    expect(chats[1]!.sessionId).not.toBe(chats[0]!.sessionId);
    expect(chats[1]!.history).toEqual([]);
  });

  it('never asks without the assistant module', async () => {
    serve({ disabled: ['assistant'] });
    renderHome();

    const input = await searchField();
    fireEvent.change(input, { target: { value: 'xyzzy' } });
    await screen.findByText(en.simple.home.noMatch);
    fireEvent.keyDown(input, { key: 'Enter' });
    await new Promise((r) => setTimeout(r, 50));
    expect(chats).toEqual([]);
    expect(screen.queryByRole('region', { name: a.label })).toBeNull();
  });
});
