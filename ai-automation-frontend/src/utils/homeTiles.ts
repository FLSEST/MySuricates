import { GitBranch, History, FolderKanban, Calendar, CheckSquare, ScrollText } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { Workflow } from '../types';
import { curateWorkflows } from './curateWorkflows';

export type NavTileId = 'workflows' | 'results' | 'projects' | 'schedules' | 'todos' | 'activity';

export interface NavTileConfig {
  id: NavTileId;
  to: string;
  icon: LucideIcon;
}

/** Curated everyday areas reachable from the Simple home — NOT all 21 routes. */
export const NAV_TILES: NavTileConfig[] = [
  { id: 'workflows', to: '/workflows', icon: GitBranch },
  { id: 'results', to: '/runs', icon: History },
  { id: 'projects', to: '/projects', icon: FolderKanban },
  { id: 'schedules', to: '/schedules', icon: Calendar },
  { id: 'todos', to: '/todos', icon: CheckSquare },
  { id: 'activity', to: '/activity', icon: ScrollText },
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
