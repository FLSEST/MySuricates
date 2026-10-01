import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { TodosWidget } from '../TodosWidget';
import { DashboardDataContext, type DashboardDataValue } from '../dashboardDataContext';
import { priorityColor, priorityFill } from '../../todo/todoConstants';
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

  it('keeps the CSS-variable fills (SVG, inline styles) on the same tones as the classes', () => {
    for (const priority of PRIORITIES) {
      const tone = priorityColor[priority].split(' ')[0]!.replace(/^bg-/, '');
      expect(priorityFill[priority]).toBe(`var(--color-${tone})`);
    }
  });
});
