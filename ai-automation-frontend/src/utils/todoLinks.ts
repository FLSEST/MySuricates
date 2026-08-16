/**
 * Deep-link to a single TODO on the todos page.
 *
 * The todos page has no per-todo route — its detail view is a modal — so the
 * link carries the id as a query param (`?todoId=`). The page resolves it via
 * `useTodo(id)` and opens the modal directly, independent of the persisted
 * filters and of which page the todo happens to live on.
 *
 * `projectId` is optional: callers that already know it (dashboard widget,
 * cockpit) pass it so the project filter is set before the todo is loaded.
 * Callers that don't (audit center) let the page derive it from the todo.
 */
export function todoDeepLink(todo: { id: string; projectId?: string | null }): string {
  const params = new URLSearchParams({ todoId: todo.id });
  if (todo.projectId) params.set('projectId', todo.projectId);
  return `/todos?${params.toString()}`;
}
