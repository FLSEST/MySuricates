import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { de } from '../../i18n/de';
import { SimpleTodos } from '../SimpleTodos';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'de' });
});

const todo = {
  id: 't-1', title: 'Steuer vorbereiten', description: null, status: 'OPEN', priority: 'MEDIUM', tags: [],
  projectId: null, projectName: null, linkedWorkflowId: null, linkedWorkflowName: null, source: 'USER',
  sourceAgent: null, sessionId: null, agentSessionRef: null, workflowRunId: null, referenceType: null,
  referenceId: null, exportedProvider: null, exportedRef: null, exportedUrl: null, fixAttempts: 0,
  lastFixRunId: null, resolutionState: null, dueDate: null,
  createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
};

describe('SimpleTodos edit dialog', () => {
  it('offers the priorities in the UI language', async () => {
    server.use(http.get('/api/v1/todos', ({ request }) => {
      const status = new URL(request.url).searchParams.get('status');
      const content = status === 'OPEN' ? [todo] : [];
      return HttpResponse.json({ content, totalElements: content.length, totalPages: 1, number: 0, size: 50 });
    }));

    renderWithProviders(<SimpleTodos />);
    fireEvent.click(await screen.findByRole('button', { name: 'Steuer vorbereiten' }));

    const dialog = await screen.findByRole('dialog');
    const labels = within(dialog).getAllByRole('option').map((o) => o.textContent);
    expect(labels).toEqual([de.todos.priorities.LOW, de.todos.priorities.MEDIUM, de.todos.priorities.HIGH, de.todos.priorities.CRITICAL]);
    expect(labels).not.toContain('MEDIUM');
  });
});

// 23635ef6: the app's own dialog instead of window.confirm, on top of the edit dialog
describe('SimpleTodos delete', () => {
  it('asks above the edit dialog; Escape only closes the question, then deleting closes both', async () => {
    const deleted: string[] = [];
    server.use(
      http.get('/api/v1/todos', ({ request }) => {
        const status = new URL(request.url).searchParams.get('status');
        const content = status === 'OPEN' && deleted.length === 0 ? [todo] : [];
        return HttpResponse.json({ content, totalElements: content.length, totalPages: 1, number: 0, size: 50 });
      }),
      http.delete('/api/v1/todos/:id', ({ params }) => {
        deleted.push(String(params.id));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithProviders(<SimpleTodos />);
    fireEvent.click(await screen.findByRole('button', { name: 'Steuer vorbereiten' }));
    const edit = await screen.findByRole('dialog', { name: de.simple.todos.editTitle });

    fireEvent.click(within(edit).getByRole('button', { name: de.common.delete }));
    const question = screen.getByRole('dialog', { name: '„Steuer vorbereiten“ löschen?' });
    expect(within(question).getByText(de.common.deleteConfirm)).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: '„Steuer vorbereiten“ löschen?' })).not.toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: de.simple.todos.editTitle })).toBeInTheDocument();
    expect(deleted).toEqual([]);

    fireEvent.click(within(edit).getByRole('button', { name: de.common.delete }));
    fireEvent.click(within(screen.getByRole('dialog', { name: '„Steuer vorbereiten“ löschen?' }))
      .getByRole('button', { name: de.common.delete }));
    await waitFor(() => expect(deleted).toEqual(['t-1']));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });
});
