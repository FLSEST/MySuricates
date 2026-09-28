import type { GoalDetail, GoalTodoItem, RatedBy } from '../../types';

/**
 * „Entschieden“ als Zustand am TODO (68a5e24e), rein und testbar. Eine Entscheidung beantwortet die
 * offene Frage: Sie schließt sie (der Server leert `openQuestion`), bleibt aber stehen, wenn später
 * eine neue Frage kommt – dann ist das TODO wieder QUESTION und zeigt die frühere Entscheidung mit.
 */

/** Wie ein TODO nach einer Entscheidung aussieht – für die sofortige Anzeige, bevor der Server antwortet. */
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

/** Das Ziel nach einer Entscheidung: das TODO geändert, die Zähler von Ziel, Projekt und Paket nachgezogen. */
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

/** Offene Fragen und Entscheidungen einer Menge TODOs, gezählt wie beim Server. */
export function decisionCounts(todos: GoalTodoItem[]): { openQuestions: number; decided: number } {
  const counted = todos.filter((todo) => todo.status !== 'ARCHIVED');
  return {
    openQuestions: counted.filter((todo) => todo.status !== 'DONE' && !!todo.openQuestion).length,
    decided: counted.filter((todo) => todo.decisionState === 'DECIDED').length,
  };
}
