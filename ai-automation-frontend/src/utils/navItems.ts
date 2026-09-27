import {
  LayoutDashboard, FolderKanban, GitBranch, History, Zap, Sparkles, Factory,
  Calendar, CalendarDays, Webhook, Plug, ScrollText, CheckSquare, Link2, BookOpen, BookMarked, BarChart2,
  MessageCircle, ClipboardList, LayoutGrid, Bug, Target,
} from 'lucide-react';
import type { SidebarEntry } from './sidebarLayout';
import type { TranslationKeys } from '../i18n/en';
import type { FeatureModuleName } from '../types';
import type { HintId } from '../data/hints';

export interface NavItemDef {
  to: string;
  icon: typeof LayoutDashboard;
  labelKey: keyof TranslationKeys['nav'];
  /** Optional module the page belongs to; hidden when the backend reports it disabled. */
  module?: FeatureModuleName;
  /** Sub-entry of this route: indented while it sits directly below it (TODO 49080447). */
  parent?: string;
  /**
   * The hint the page renders to explain itself. Required: no menu entry without an explanation;
   * `navHints.test.ts` checks that the page really renders it (TODO 1df94028).
   */
  hint: HintId;
}

export const navItems: NavItemDef[] = [
  { to: '/', icon: LayoutDashboard, labelKey: 'dashboard', hint: 'dashboard-views' },
  { to: '/projects', icon: FolderKanban, labelKey: 'projects', hint: 'projects-directory' },
  { to: '/todos', icon: CheckSquare, labelKey: 'todos', hint: 'todos-sources' },
  { to: '/goals', icon: Target, labelKey: 'goals', parent: '/todos', hint: 'goals-overview' },
  { to: '/workflows', icon: GitBranch, labelKey: 'workflows', hint: 'workflows-start' },
  { to: '/runs', icon: History, labelKey: 'runs', hint: 'runs-list' },
  { to: '/debug', icon: Bug, labelKey: 'debug', module: 'debug', hint: 'debug-failing' },
  { to: '/chains', icon: Link2, labelKey: 'chains', module: 'chains', hint: 'chains-sequence' },
  { to: '/cockpit', icon: LayoutGrid, labelKey: 'cockpit', hint: 'cockpit-overview' },
  { to: '/calendar', icon: CalendarDays, labelKey: 'calendar', hint: 'calendar-overview' },
  { to: '/schedules', icon: Calendar, labelKey: 'schedules', hint: 'schedules-timing' },
  { to: '/webhooks', icon: Webhook, labelKey: 'webhooks', module: 'webhooks', hint: 'webhooks-trigger' },
  { to: '/integrations', icon: Plug, labelKey: 'integrations', module: 'integrations', hint: 'integrations-connect' },
  { to: '/onboarding-profiles', icon: BookMarked, labelKey: 'onboardingProfiles', module: 'onboarding-profiles', hint: 'onboarding-profiles' },
  { to: '/wishes', icon: Sparkles, labelKey: 'wishes', module: 'wishes', hint: 'wishes-loop' },
  { to: '/factory', icon: Factory, labelKey: 'factory', module: 'factory', hint: 'factory-create' },
  { to: '/dialogs', icon: MessageCircle, labelKey: 'dialogs', module: 'dialog-import', hint: 'dialogs-sessions' },
  { to: '/skills', icon: Zap, labelKey: 'skills', module: 'skills', hint: 'skills-packages' },
  { to: '/insights', icon: BarChart2, labelKey: 'insights', module: 'learning', hint: 'insights-numbers' },
  { to: '/activity', icon: ScrollText, labelKey: 'activityLog', hint: 'activity-events' },
  { to: '/docs', icon: BookOpen, labelKey: 'docs', hint: 'docs-reading' },
  { to: '/reports', icon: ClipboardList, labelKey: 'reports', module: 'audit', hint: 'audit-findings' },
];

/**
 * Whether the entry at `index` is shown as a sub-entry: it has a parent and follows it directly in
 * the list as rendered. Moved elsewhere (or with the parent hidden) it is an ordinary entry.
 */
export function isNestedAt(entries: readonly SidebarEntry<NavItemDef>[], index: number): boolean {
  const entry = entries[index];
  const previous = entries[index - 1];
  return entry?.kind === 'item' && !!entry.item.parent && previous?.kind === 'item' && previous.item.to === entry.item.parent;
}
