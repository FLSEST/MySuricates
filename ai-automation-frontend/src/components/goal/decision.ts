import type { GoalDetail, GoalTodoItem, RatedBy } from '../../types';

/**
 * „Entschieden“ as a state on the TODO (68a5e24e), pure and testable. A decision answers the
 * open question: it closes it (the server clears `openQuestion`), but stays if later
 * a new question comes - then the TODO is QUESTION again and shows the earlier decision too.
 */

/** What a TODO looks like after a decision - for immediate display, before the server answers. */
export function applyDecision(todo: GoalTodoItem, decision: string, now: string, by: RatedBy = 'USER'): GoalTodoItem {
  const text = decision.trim();
  if (!text) {
    return {
      ...todo, decision: null, decidedAt: null, decidedBy: null,
      decisionState: todo.openQuestion ? 'QUESTION' : 'NONE',
    };
  }
  return { ...todo, decision: text, decidedAt: now, decidedBy: by, openQuestion: null, decisionState: 'DECIDED' };
}

/** The goal after a decision: the TODO changed, the counters of goal, project and package updated. */
export function applyDecisionToDetail(detail: GoalDetail, todoId: string, decision: string, now: string): GoalDetail {
  const before = detail.todos.find((todo) => todo.id === todoId);
  if (!before) return detail;
  const after = applyDecision(before, decision, now);
  const counted = (todo: GoalTodoItem) => todo.status !== 'ARCHIVED';
  const decidedDelta = (counted(before) ? Number(after.decisionState === 'DECIDED') - Number(before.decisionState === 'DECIDED') : 0);
  const asks = (todo: GoalTodoItem) => counted(todo) && todo.status !== 'DONE' && !!todo.openQuestion;
  const questionDelta = Number(asks(after)) - Number(asks(before));
  return {
    ...detail,
    goal: {
      ...detail.goal,
      progress: {
        ...detail.goal.progress,
        openQuestions: detail.goal.progress.openQuestions + questionDelta,
        decided: (detail.goal.progress.decided ?? 0) + decidedDelta,
      },
    },
    todos: detail.todos.map((todo) => (todo.id === todoId ? after : todo)),
    projects: detail.projects.map((project) => (project.projectId === before.projectId
      ? { ...project, openQuestions: project.openQuestions + questionDelta, decided: project.decided + decidedDelta }
      : project)),
    workPackages: detail.workPackages.map((item) => (item.id === before.workPackageId
      ? { ...item, openQuestions: item.openQuestions + questionDelta, decided: item.decided + decidedDelta }
      : item)),
  };
}

/** Open questions and decisions of a set of TODOs, counted like on the server. */
export function decisionCounts(todos: GoalTodoItem[]): { openQuestions: number; decided: number } {
  const counted = todos.filter((todo) => todo.status !== 'ARCHIVED');
  return {
    openQuestions: counted.filter((todo) => todo.status !== 'DONE' && !!todo.openQuestion).length,
    decided: counted.filter((todo) => todo.decisionState === 'DECIDED').length,
  };
}
