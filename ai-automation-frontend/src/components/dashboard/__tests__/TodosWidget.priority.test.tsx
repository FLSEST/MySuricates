import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { TodosWidget } from '../TodosWidget';
import { DashboardDataContext, type DashboardDataValue } from '../dashboardDataContext';
import { PRIORITY_TONES, priorityColor, priorityFill } from '../../todo/todoConstants';
import type { Todo, TodoPriority } from '../../../types';

// d82ee18e: the dashboard widget used its own priority colours (MEDIUM = accent instead of warning).
const PRIORITIES: TodoPriority[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

function todo(priority: TodoPriority): Todo {
  return {
    id: `t-${priority}`, title: `${priority} todo`, description: null, status: 'OPEN', priority, tags: [],
    projectId: null, projectName: null, linkedWorkflowId: null, linkedWorkflowName: null, source: 'USER',
    sourceAgent: null, workflowRunId: null, referenceType: null, referenceId: null, fixAttempts: 0,
    lastFixRunId: null, resolutionState: 'IDLE', dueDate: null,
    createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
  } as Todo;
}

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  server.use(
    http.get('/api/v1/todos/count', () => HttpResponse.json({ open: 4, inProgress: 0, done: 0, backlog: 0, archived: 0 })),
    http.get('/api/v1/todos', () =>
      HttpResponse.json({ content: PRIORITIES.map(todo), totalElements: 4, totalPages: 1, number: 0, size: 5 })),
  );
});

const value: DashboardDataValue = {
  data: undefined, isLoading: false, error: null, refetch: () => {},
  filter: {}, days: 30, isPlaceholderData: false, dataDays: 30,
};

describe('todo priority colours', () => {
  it('TodosWidget marks each priority with the shared colour from todoConstants', async () => {
    renderWithProviders(<DashboardDataContext.Provider value={value}><TodosWidget /></DashboardDataContext.Provider>);
    await screen.findByText('MEDIUM todo');
    const labels = { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low' } as const;
    for (const priority of PRIORITIES) {
      const marker = screen.getByTitle(labels[priority]);
      for (const cls of priorityColor[priority].split(' ')) expect(marker.className).toContain(cls);
    }
    expect(screen.getByTitle('Medium').className).toContain('bg-warning');
  });

  it('derives classes and SVG fills from the one tone table', () => {
    for (const priority of PRIORITIES) {
      const [bg, ...ring] = priorityColor[priority].split(' ');
      const token = bg!.replace(/^bg-/, '');
      // class and SVG fill name the same theme token; the ring only where the table rings
      expect(priorityFill[priority]).toBe(`var(--color-${token})`);
      expect(ring).toEqual(PRIORITY_TONES[priority].ringed ? ['ring-2', `ring-${token}/40`] : []);
    }
    // CRITICAL shares HIGH's tone and is told apart by its ring
    expect(priorityColor.CRITICAL).not.toBe(priorityColor.HIGH);
    expect(priorityColor).toEqual({
      CRITICAL: 'bg-danger ring-2 ring-danger/40', HIGH: 'bg-danger', MEDIUM: 'bg-warning', LOW: 'bg-text-tertiary',
    });
  });
});
