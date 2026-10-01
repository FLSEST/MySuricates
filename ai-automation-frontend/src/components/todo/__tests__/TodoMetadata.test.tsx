import { afterEach, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { en } from '../../../i18n/en';
import type { Todo } from '../../../types';
import { TodoMetadata } from '../TodoMetadata';

const todo = {
  id: 't1', title: 'Fix it', description: null, status: 'OPEN', priority: 'MEDIUM', tags: [], projectId: null,
  projectName: null, linkedWorkflowId: null, linkedWorkflowName: null, source: 'WORKFLOW', sourceAgent: null,
  workflowRunId: 'run-1', referenceType: null, referenceId: null, fixAttempts: 1, lastFixRunId: 'run-2',
  resolutionState: 'IDLE', dueDate: null, createdAt: '2026-09-01T10:00:00Z', updatedAt: '2026-09-02T10:00:00Z',
} as Todo;

afterEach(() => useI18nStore.setState({ locale: 'en' }));

// f206504e: "Run:" and "Run" were fixed text.
describe('TodoMetadata', () => {
  it.each([['en', en], ['de', de]] as const)('labels the origin and fix runs in the UI language (%s)', (locale, texts) => {
    useI18nStore.setState({ locale });
    renderWithProviders(<TodoMetadata todo={todo} />);

    expect(screen.getByText(`${texts.todos.originRun}:`)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: texts.todos.run })).toHaveAttribute('href', '/runs/run-1');
    expect(screen.getByRole('link', { name: texts.todos.fixRun })).toHaveAttribute('href', '/runs/run-2');
  });
});
