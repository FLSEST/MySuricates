import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { GoalTimeline } from '../GoalTimeline';
import { REVEAL_MS, revealElement } from '../goalReveal';
import type { Goal, GoalProject, GoalTodoItem, GoalWorkPackage } from '../../../types';

// 85889063 (Flo): „Diese 8 ohne Datum, die sind ziemlich versteckt so.“

beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const NOW = new Date('2030-10-01T10:00:00');

const goal: Goal = {
  id: 'g1', title: 'Launch', description: null, status: 'ACTIVE', targetDate: null, createdAt: '', updatedAt: '',
  progress: { total: 8, open: 8, inProgress: 0, backlog: 0, done: 0, openQuestions: 0, decided: 0, percentDone: 0 },
  guard: { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null },
};
const projects: GoalProject[] = [
  { projectId: 'p1', name: 'AutoPilot', archived: false, position: 0, total: 8, done: 0, openQuestions: 0, decided: 0 },
];
const workPackages: GoalWorkPackage[] = [{
  id: 'w1', goalId: 'g1', title: 'Repo clean', projectId: 'p1', position: 0,
  startDate: new Date('2030-10-01T00:00:00').toISOString(), endDate: new Date('2030-10-07T23:59:59').toISOString(),
  state: 'OPEN', total: 6, done: 0, openQuestions: 0, decided: 0, completedAt: null, reopened: false, createdAt: '', updatedAt: '',
}];

function item(id: string, workPackageId: string | null): GoalTodoItem {
  return {
    id, title: `Task ${id}`, status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '', dueDate: null, workPackageId,
  };
}

// sechs ohne Datum im Paket (mehr als fünf: eingeklappt), zwei ohne Paket (offen)
const todos = [...['1', '2', '3', '4', '5', '6'].map((id) => item(`w${id}`, 'w1')), item('l1', null), item('l2', null)];

function renderTimeline() {
  return renderWithProviders(
    <GoalTimeline goal={goal} todos={todos} workPackages={workPackages} milestones={[]} projects={projects} onOpenTodo={vi.fn()} now={NOW} />,
  );
}

const packageToggle = () => within(screen.getByTestId('timeline-row-p1-w1')).getByRole('button', { name: '6 TODOs without a date' });
const looseToggle = () => screen.getByRole('button', { name: '2 TODOs without a date' });

describe('TODOs without a date in the timeline', () => {
  it('shows a clear counter button per row; rows with up to five stand open, bigger ones folded', () => {
    renderTimeline();

    expect(packageToggle()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: 'Task w1 – no due date' })).not.toBeInTheDocument();
    expect(looseToggle()).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('button', { name: 'Task l1 – no due date' })).toBeInTheDocument();

    fireEvent.click(packageToggle());
    expect(packageToggle()).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('button', { name: 'Task w6 – no due date' })).toBeInTheDocument();
  });

  it('the switch at the top shows all or folds all, and the browser remembers it per goal', () => {
    const first = renderTimeline();
    const switchRow = screen.getByTestId('timeline-undated-switch');
    expect(switchRow).toHaveTextContent('TODOs without a date (8):');

    fireEvent.click(within(switchRow).getByRole('button', { name: 'Show all TODOs without a date' }));
    expect(packageToggle()).toHaveAttribute('aria-expanded', 'true');
    expect(looseToggle()).toHaveAttribute('aria-expanded', 'true');
    expect(JSON.parse(localStorage.getItem('mysuricates-goal-undated')!)).toEqual({ g1: 'all' });

    first.unmount();
    renderTimeline();
    expect(packageToggle()).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(screen.getByRole('button', { name: 'Collapse all TODOs without a date' }));
    expect(packageToggle()).toHaveAttribute('aria-expanded', 'false');
    expect(looseToggle()).toHaveAttribute('aria-expanded', 'false');
    expect(JSON.parse(localStorage.getItem('mysuricates-goal-undated')!)).toEqual({ g1: 'none' });

    // eine einzelne Zeile lässt sich danach für sich öffnen, der Schalter bietet wieder „alle zeigen“
    fireEvent.click(looseToggle());
    expect(looseToggle()).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('button', { name: 'Show all TODOs without a date' })).toBeInTheDocument();
  });

  it('has no switch when every TODO has a date', () => {
    renderWithProviders(
      <GoalTimeline goal={goal} todos={[]} workPackages={workPackages} milestones={[]} projects={projects} onOpenTodo={vi.fn()} now={NOW} />,
    );
    expect(screen.queryByTestId('timeline-undated-switch')).not.toBeInTheDocument();
  });
});

describe('revealElement', () => {
  afterEach(() => vi.useRealTimers());

  const setup = (hidden = false) => {
    document.body.innerHTML = `<div ${hidden ? 'hidden' : ''}><div data-todo-id="t1">Task</div></div>`;
    const element = document.querySelector<HTMLElement>('[data-todo-id="t1"]')!;
    element.scrollIntoView = vi.fn();
    return element;
  };

  it('leaves a hidden TODO alone – the view first has to open it', () => {
    const element = setup(true);
    expect(revealElement(document.body, 't1')).toBe(false);
    expect(element.scrollIntoView).not.toHaveBeenCalled();
  });

  it('scrolls to it and highlights it for about two seconds', () => {
    vi.useFakeTimers();
    const element = setup();
    expect(revealElement(document.body, 't1')).toBe(true);
    expect(element.scrollIntoView).toHaveBeenCalledWith({ block: 'center', inline: 'center', behavior: 'smooth' });
    expect(element).toHaveAttribute('data-goal-revealed');
    vi.advanceTimersByTime(REVEAL_MS);
    expect(element).not.toHaveAttribute('data-goal-revealed');
  });

  it('jumps without gliding when less motion is wanted', () => {
    const matchMedia = vi.spyOn(window, 'matchMedia').mockImplementation((query: string) =>
      ({ matches: query.includes('reduce'), media: query } as MediaQueryList));
    const element = setup();
    revealElement(document.body, 't1');
    expect(element.scrollIntoView).toHaveBeenCalledWith({ block: 'center', inline: 'center', behavior: 'auto' });
    matchMedia.mockRestore();
  });
});
