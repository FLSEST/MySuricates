import { GitBranch, History, FolderKanban, Calendar, CheckSquare, ScrollText, Factory, Plug, FileText, Lightbulb } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { Workflow } from '../types';
import { curateWorkflows } from './curateWorkflows';

export type NavTileId =
  | 'workflows' | 'results' | 'projects' | 'schedules' | 'todos' | 'activity'
  | 'factory' | 'integrations' | 'reports' | 'wishes';

export interface NavTileConfig {
  id: NavTileId;
  to: string;
  icon: LucideIcon;
  /** Accent hue for this tile (works on light + dark). */
  color: string;
}

/** Shared accent hue per area — single source for tiles and Simple sub-pages. */
export const AREA_COLORS: Record<NavTileId, string> = {
  workflows: 'var(--color-chart-1)',
  results: 'var(--color-success)',
  projects: 'var(--color-chart-2)',
  schedules: 'var(--color-chart-3)',
  todos: 'var(--color-warning)',
  activity: 'var(--color-info)',
  factory: 'var(--color-chart-5)',
  integrations: 'var(--color-chart-6)',
  reports: 'var(--color-chart-4)',
  wishes: 'var(--color-chart-7)',
};

/** Curated everyday areas reachable from the Simple home — NOT all 21 routes. */
export const NAV_TILES: NavTileConfig[] = [
  { id: 'workflows', to: '/workflows', icon: GitBranch, color: AREA_COLORS.workflows },
  { id: 'results', to: '/runs', icon: History, color: AREA_COLORS.results },
  { id: 'projects', to: '/projects', icon: FolderKanban, color: AREA_COLORS.projects },
  { id: 'schedules', to: '/schedules', icon: Calendar, color: AREA_COLORS.schedules },
  { id: 'todos', to: '/todos', icon: CheckSquare, color: AREA_COLORS.todos },
  { id: 'activity', to: '/activity', icon: ScrollText, color: AREA_COLORS.activity },
  { id: 'factory', to: '/factory', icon: Factory, color: AREA_COLORS.factory },
  { id: 'integrations', to: '/integrations', icon: Plug, color: AREA_COLORS.integrations },
  { id: 'reports', to: '/reports', icon: FileText, color: AREA_COLORS.reports },
  { id: 'wishes', to: '/wishes', icon: Lightbulb, color: AREA_COLORS.wishes },
];

export interface HomeTiles {
  navTiles: NavTileConfig[];
  runTiles: Workflow[];
}

/** Fixed nav tiles + top-N run-workflow tiles (pinned ∪ most-used). Pure function. */
export function buildHomeTiles(workflows: Workflow[], pinnedIds: string[], runCount = 4): HomeTiles {
  return {
    navTiles: NAV_TILES,
    runTiles: curateWorkflows(workflows, pinnedIds, runCount).curated,
  };
}

export type GreetingKey = 'greetingMorning' | 'greetingDay' | 'greetingEvening';

/** Time-of-day greeting key (no user name exists in the app). Pure function. */
export function greetingKey(date: Date): GreetingKey {
  const h = date.getHours();
  if (h < 11) return 'greetingMorning';
  if (h < 18) return 'greetingDay';
  return 'greetingEvening';
}
