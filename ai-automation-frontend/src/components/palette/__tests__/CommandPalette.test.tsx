import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import { useAssistantStore } from '../../../stores/assistantStore';
import { CommandPalette } from '../CommandPalette';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const workflow = (id: string, name: string, blocks: unknown[] = []) => ({
  id, projectId: 'p1', projectName: 'P', name, description: null, icon: null, blocks, version: '1', metadata: {},
  activatedSkillIds: [], activatedSkillNames: [], runCount: 0, instructions: null, model: null, templateId: null,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
});

let executed: string[];
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useAssistantStore.setState({ open: false, pendingInput: null });
  // Pinned first, then the recents: "Deploy app" is row 1, "Backup db" (needs a variable) row 2
  localStorage.setItem('workflows-pins', JSON.stringify(['w1']));
  localStorage.setItem('workflows-recents', JSON.stringify(['w2']));
  executed = [];
  server.use(
    http.get('/api/v1/workflows', () => HttpResponse.json([
      workflow('w1', 'Deploy app'),
      workflow('w2', 'Backup db', [{ id: 'b1', type: 'shell', label: 'Backup', config: { command: 'backup {{ target }}' } }]),
    ])),
    http.post('/api/v1/workflows/:id/execute', ({ params }) => {
      executed.push(String(params.id));
      return HttpResponse.json({ id: 'run-1', workflowId: params.id, status: 'RUNNING', steps: [] });
    }),
    http.post('/api/v1/search/workflows/semantic', () => HttpResponse.json([])),
  );
});

function Location() {
  return <div data-testid="location">{useLocation().pathname}</div>;
}

function renderPalette() {
  const onClose = vi.fn();
  renderWithProviders(<><CommandPalette open onClose={onClose} /><Location /></>);
  return { onClose };
}

const row = (title: string) => screen.getByText(title).closest('.cursor-pointer')!;
const key = (k: string, init: KeyboardEventInit = {}) => fireEvent.keyDown(window, { key: k, ...init });

describe('CommandPalette', { timeout: 15_000 }, () => {
  it('moves over pinned and recent workflows and runs the chosen one with Enter', async () => {
    const { onClose } = renderPalette();
    await screen.findByText('Backup db');
    expect(row('Deploy app')).toHaveClass('bg-accent/10');
    key('ArrowDown');
    expect(row('Backup db')).toHaveClass('bg-accent/10');
    expect(row('Deploy app')).not.toHaveClass('bg-accent/10');
    key('ArrowUp');
    key('Enter');

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/workflows/w1/run/run-1'));
    expect(executed).toEqual(['w1']);
    expect(onClose).toHaveBeenCalled();
  });

  it('opens the page with Shift+Enter and a new tab with Cmd+Enter', async () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    const { onClose } = renderPalette();
    await screen.findByText('Deploy app');
    key('Enter', { metaKey: true });
    expect(open).toHaveBeenCalledWith('/workflows/w1', '_blank', 'noopener');
    key('Enter', { shiftKey: true });
    expect(screen.getByTestId('location')).toHaveTextContent('/workflows/w1');
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(executed).toEqual([]);
    open.mockRestore();
  });

  it('asks for the variables first; Escape then closes only the form', async () => {
    const { onClose } = renderPalette();
    await screen.findByText('Backup db');
    key('2', { altKey: true });
    expect(await screen.findByText(en.commandPalette.collectVariablesHeader)).toBeInTheDocument();
    expect(screen.getByText('target')).toBeInTheDocument();

    key('Escape');
    expect(screen.queryByText(en.commandPalette.collectVariablesHeader)).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
    key('Escape');
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(executed).toEqual([]);
  });

  it('starts a new query at the top and hands it to the assistant from its row', async () => {
    const { onClose } = renderPalette();
    await screen.findByText('Backup db');
    key('ArrowDown');
    const input = screen.getByPlaceholderText(en.commandPalette.contexts.workflow.placeholder);
    fireEvent.change(input, { target: { value: 'Deploy' } });
    // The match is highlighted, so the row's own text is what follows "Deploy"
    expect(await screen.findByText('app')).toBeInTheDocument();
    expect(screen.getByText('app').closest('.cursor-pointer')).toHaveClass('bg-accent/10');
    expect(screen.getByText(`${en.commandPalette.askAssistant}:`)).toBeInTheDocument();

    // Results follow the query debounced; once none match, the assistant row is the first one
    fireEvent.change(input, { target: { value: 'zz' } });
    expect(await screen.findByText(en.commandPalette.contexts.workflow.empty)).toBeInTheDocument();
    key('Enter');
    expect(useAssistantStore.getState()).toMatchObject({ pendingInput: 'zz', open: true });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
