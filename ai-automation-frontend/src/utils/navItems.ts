import {
  LayoutDashboard, FolderKanban, GitBranch, History, Zap, Sparkles, Factory,
  Calendar, CalendarDays, Webhook, Plug, ScrollText, CheckSquare, Link2, BookOpen, BookMarked, BarChart2,
  MessageCircle, ClipboardList, LayoutGrid, Bug, Target,
} from 'lucide-react';
import type { SidebarEntry } from './sidebarLayout';
import type { TranslationKeys } from '../i18n/en';
import type { FeatureModuleName } from '../types';

export interface NavItemDef {
  to: string;
  icon: typeof LayoutDashboard;
  labelKey: keyof TranslationKeys['nav'];
  /** Optional module the page belongs to; hidden when the backend reports it disabled. */
  module?: FeatureModuleName;
  /** Sub-entry of this route: indented while it sits directly below it (TODO 49080447). */
  parent?: string;
}

export const navItems: NavItemDef[] = [
  { to: '/', icon: LayoutDashboard, labelKey: 'dashboard' },
  { to: '/projects', icon: FolderKanban, labelKey: 'projects' },
  { to: '/todos', icon: CheckSquare, labelKey: 'todos' },
  { to: '/goals', icon: Target, labelKey: 'goals', parent: '/todos' },
  { to: '/workflows', icon: GitBranch, labelKey: 'workflows' },
  { to: '/runs', icon: History, labelKey: 'runs' },
  { to: '/debug', icon: Bug, labelKey: 'debug', module: 'debug' },
  { to: '/chains', icon: Link2, labelKey: 'chains', module: 'chains' },
  { to: '/cockpit', icon: LayoutGrid, labelKey: 'cockpit' },
  { to: '/calendar', icon: CalendarDays, labelKey: 'calendar' },
  { to: '/schedules', icon: Calendar, labelKey: 'schedules' },
  { to: '/webhooks', icon: Webhook, labelKey: 'webhooks', module: 'webhooks' },
  { to: '/integrations', icon: Plug, labelKey: 'integrations', module: 'integrations' },
  { to: '/onboarding-profiles', icon: BookMarked, labelKey: 'onboardingProfiles', module: 'onboarding-profiles' },
  { to: '/wishes', icon: Sparkles, labelKey: 'wishes', module: 'wishes' },
  { to: '/factory', icon: Factory, labelKey: 'factory', module: 'factory' },
  { to: '/dialogs', icon: MessageCircle, labelKey: 'dialogs', module: 'dialog-import' },
  { to: '/skills', icon: Zap, labelKey: 'skills', module: 'skills' },
  { to: '/insights', icon: BarChart2, labelKey: 'insights', module: 'learning' },
  { to: '/activity', icon: ScrollText, labelKey: 'activityLog' },
  { to: '/docs', icon: BookOpen, labelKey: 'docs' },
  { to: '/reports', icon: ClipboardList, labelKey: 'reports', module: 'audit' },
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
