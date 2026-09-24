import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, fireEvent, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { GoalRelationMap } from '../GoalRelationMap';
import type { GoalRelations, GoalTodoRelation } from '../../../types';

beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

function relation(id: string, overrides: Partial<GoalTodoRelation> = {}): GoalTodoRelation {
  return {
    id, title: `Todo ${id}`, status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, candidate: false,
    origin: { kind: 'USER', workflowId: null, workflowName: null, runId: null, runStatus: null, findingIds: [], agent: null },
    workers: [], result: null, ...overrides,
  };
}

const fixer = { kind: 'TRIGGER' as const, workflowId: 'wf-fix', workflowName: 'Release Fixer', triggerId: 'tr', triggerLabel: 'release' };

const relations: GoalRelations = {
  goalId: 'g1',
  activeTriggers: 1,
  todos: [
    relation('gate', {
      title: 'Privacy findings',
      origin: { kind: 'WORKFLOW_RUN', workflowId: 'wf-gate', workflowName: 'Quality Gate', runId: 'r-gate', runStatus: 'FAILED', findingIds: [], agent: null },
      workers: [fixer],
      result: { runId: 'r-fix', workflowId: 'wf-fix', runStatus: 'COMPLETED', at: null, fixAttempts: 2, resolutionState: 'SUCCEEDED' },
    }),
    relation('mine', { title: 'Style probe' }),
    relation('cand', { title: 'Release notes', candidate: true, workers: [fixer] }),
  ],
};

function renderMap(overrides: Partial<GoalRelations> = {}, handlers = { onOpenTodo: vi.fn(), onAssign: vi.fn() }) {
  renderWithProviders(
    <Routes>
      <Route path="/goals/g1" element={<GoalRelationMap relations={{ ...relations, ...overrides }} {...handlers} />} />
      <Route path="/workflows/:id" element={<p>workflow page</p>} />
      <Route path="/runs/:id" element={<p>run page</p>} />
    </Routes>,
    { route: '/goals/g1' },
  );
  return handlers;
}

const node = (id: string) => document.querySelector(`[data-node="${id}"]`) as SVGGElement;

describe('GoalRelationMap', () => {
  it('draws the four columns with the merged workflow nodes, the gap and the candidate', () => {
    renderMap();

    const map = screen.getByTestId('goal-relation-map');
    for (const column of ['Origin', 'TODOs', 'Processing', 'Result']) expect(within(map).getByText(column)).toBeInTheDocument();
    expect(within(map).getByText('Quality Gate')).toBeInTheDocument();
    expect(within(map).getByText('Release Fixer')).toBeInTheDocument();
    // Text plus gleichlautender Tooltip (<title>)
    expect(within(map).getAllByText('no workflow').length).toBeGreaterThan(0);
    expect(within(map).getAllByText('Done · 2×').length).toBeGreaterThan(0);
    expect(within(map).getByText('Might belong here')).toBeInTheDocument();
    expect(node('todo:cand').querySelector('rect')).toHaveAttribute('stroke-dasharray', '5 4');
  });

  it('highlights the whole path of a todo on hover and dims the rest', () => {
    renderMap();

    fireEvent.mouseEnter(node('todo:gate'));

    expect(node('origin:wf:wf-gate')).toHaveAttribute('opacity', '1');
    expect(node('worker:wf-fix')).toHaveAttribute('opacity', '1');
    expect(node('result:gate')).toHaveAttribute('opacity', '1');
    expect(node('todo:mine')).toHaveAttribute('opacity', '0.25');
    expect(node('gap:mine')).toHaveAttribute('opacity', '0.25');
  });

  it('opens a todo, assigns a candidate and follows workflow and run links', () => {
    const handlers = renderMap();

    fireEvent.click(screen.getByRole('button', { name: 'Style probe' }));
    expect(handlers.onOpenTodo).toHaveBeenCalledWith('mine');

    fireEvent.click(screen.getByRole('button', { name: 'Add: Release notes' }));
    expect(handlers.onAssign).toHaveBeenCalledWith('cand');
    expect(handlers.onOpenTodo).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: /Done · 2×/ }));
    expect(screen.getByText('run page')).toBeInTheDocument();
  });

  it('follows a workflow node to the workflow', () => {
    renderMap();
    fireEvent.click(screen.getByRole('button', { name: /Release Fixer/ }));
    expect(screen.getByText('workflow page')).toBeInTheDocument();
  });

  it('explains how workflows dock on while none works on the goal todos', () => {
    renderMap({ todos: [relation('mine'), relation('cand', { candidate: true, workers: [fixer] })] });

    const hint = screen.getByTestId('goal-map-no-workers');
    expect(hint).toHaveTextContent('TodoTrigger (tag, project, priority)');
    expect(within(hint).getByRole('link', { name: 'Manage TodoTriggers' })).toHaveAttribute('href', '/todos#todo-triggers');
  });

  it('shows no dock-on hint once a workflow works on a goal todo', () => {
    renderMap();
    expect(screen.queryByTestId('goal-map-no-workers')).not.toBeInTheDocument();
  });
});
