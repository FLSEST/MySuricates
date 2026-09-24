import type { TranslationKeys } from '../../i18n';
import type { PendingTool } from '../../hooks/useAssistant';

type TextKey = Exclude<keyof TranslationKeys['assistant']['pendingToolText'], 'quoted' | 'byId'>;

const KNOWN: ReadonlySet<string> = new Set<TextKey>([
  'create_todo', 'update_todo_status', 'create_workflow', 'update_workflow',
  'delete_workflow', 'start_workflow', 'cancel_run', 'create_chain',
]);

/** Proposals whose card is visually marked as destructive. */
export function isDestructivePendingTool(tool: PendingTool): boolean {
  return tool.type === 'delete_workflow';
}

/**
 * Confirmation sentence of a proposed assistant action in the UI language (TODO 4e152029):
 * built from `type` + `subject` + `params`, with priority/status translated. Unknown types or a
 * missing subject (older backend) fall back to the backend's English `description`.
 */
export function pendingToolText(tool: PendingTool, t: TranslationKeys): string {
  const subjectRaw = tool.subject?.trim();
  if (!KNOWN.has(tool.type) || !subjectRaw) return tool.description;

  const texts = t.assistant.pendingToolText;
  const params = tool.params ?? {};
  const byId = params.ref === 'id';
  const subject = byId
    ? fill(texts.byId, { id: subjectRaw })
    : fill(texts.quoted, { name: subjectRaw });

  let key = tool.type as TextKey;
  if (key === 'cancel_run' && byId) key = 'cancel_run_by_id';

  const priorities = t.todos.priorities as Record<string, string>;
  const statuses = t.todos.statuses as Record<string, string>;
  const priority = params.priority ? (priorities[params.priority] ?? params.priority) : t.todos.priorities.MEDIUM;
  const status = params.status ? (statuses[params.status] ?? params.status) : '?';

  return fill(texts[key], { subject, priority, status });
}

/** Single-pass placeholder fill — names containing `$&` or `{status}` stay literal. */
function fill(template: string, values: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (match, name: string) => values[name] ?? match);
}
