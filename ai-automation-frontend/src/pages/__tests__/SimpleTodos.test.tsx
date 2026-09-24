import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
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
