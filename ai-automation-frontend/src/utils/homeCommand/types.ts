import type { Locale } from '../../i18n';
import type { HintId } from '../../data/hints';
import type { Workflow } from '../../types';

/**
 * Shared shapes of the Simple home's command field (`components/simple/home-command`): the query as
 * the matchers read it, and the hits the sources hand to the field. Types only — no I/O.
 */

/** The result groups, one per source. */
export type GroupId =
  | 'workflows' | 'approvals' | 'runs' | 'templates' | 'todos'
  | 'schedules' | 'projects' | 'explanations' | 'actions';

/** What a keyword in the query asks for (de + en), deterministic — no AI. */
export type Intent =
  | 'failed' | 'schedule' | 'waiting' | 'todo' | 'locale' | 'theme' | 'settings' | 'create' | 'explain';

export interface ParsedQuery {
  /** The input as typed, trimmed. */
  raw: string;
  /** Normalized search words (lower case, no diacritics), stop words dropped; intent keywords stay. */
  tokens: string[];
  /** `tokens` without the intent keywords — what the text should be about ("backup" in "backup failed"). */
  contentTokens: string[];
  /** Intents in the order their keywords appear. */
  intents: Intent[];
  /** Days from today a time word names (heute 0, morgen 1, Freitag …); null without one. */
  dayOffset: number | null;
  /** Text after an "aufgabe:" / "todo:" / "task:" prefix; null without the prefix. */
  todoText: string | null;
}

/** Icon of a hit: a fixed key the row maps to an icon, or the entry's own emoji. */
export type HomeIcon =
  | 'workflow' | 'template' | 'pool' | 'run' | 'run-failed' | 'approval' | 'input' | 'todo' | 'todo-add'
  | 'project' | 'schedule' | 'schedule-add' | 'language' | 'theme' | 'settings' | 'hint' | 'pro'
  | 'project-add' | 'workflow-add' | 'explanation'
  | { emoji: string };

/** What activating a hit does. The field executes it (`useHomeActions`); sources only describe it. */
export type HomeAction =
  | { kind: 'navigate'; to: string }
  | { kind: 'run-workflow'; workflow: Workflow }
  | { kind: 'retry-run'; runId: string }
  | { kind: 'answer-approval'; runId: string; answer: string }
  | { kind: 'create-todo'; title: string }
  | { kind: 'set-locale'; locale: Locale }
  | { kind: 'toggle-theme' }
  | { kind: 'reset-hints' }
  | { kind: 'open-in-pro'; to?: string }
  | { kind: 'toggle-explanation'; hintId: HintId };

export interface HomeButton {
  id: string;
  label: string;
  action: HomeAction;
  /** `danger` for a rejecting approval option, `human` for the other answers. */
  tone?: 'neutral' | 'danger' | 'human';
}

export interface HomeItem {
  /** Unique across all groups. */
  id: string;
  icon: HomeIcon;
  label: string;
  sub?: string;
  /** Run status for the status dot. */
  status?: string;
  /** Leads to a Pro page (ProBadge). */
  pro?: boolean;
  /** Someone waits for a decision — rendered in the `human` role colour. */
  human?: boolean;
  /** What Enter or a click on the row does; `label` is the row's main button. */
  primary: { label: string; action: HomeAction };
  secondary?: HomeButton[];
  /** Explanation bullets, shown when the item is expanded. */
  points?: string[];
  /** Generated from the query itself ("add as task: …") — not a found hit. */
  fallback?: boolean;
}

/** What one source hook returns. `items` are all its hits, best first; the field trims them. */
export interface HomeSource {
  id: GroupId;
  items: HomeItem[];
  loading: boolean;
  /** The Simple page with everything of this kind ("Show all"). */
  showAllTo?: string;
  /** The source knows of more hits than it returned (server-side search). */
  more?: boolean;
}

/** A group as the field shows it. */
export interface HomeGroup {
  id: GroupId;
  items: HomeItem[];
  showAllTo?: string;
  /** Hits were left out — "Show all" is worth offering. */
  more: boolean;
}
