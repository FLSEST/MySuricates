import type { PaletteContextId } from './contexts/types';

/** Order of the contexts in the palette's tab bar and for Tab / Shift+Tab. */
export const CONTEXT_ORDER: PaletteContextId[] = ['workflow', 'run', 'project'];

/**
 * Pure mapping from current route to the palette context id. Called both by the
 * palette itself (via useLocation) and by the Header trigger (to render the
 * per-route label). Unknown routes fall back to the workflow context — it is the
 * most broadly useful default.
 */
export function resolveContextId(pathname: string): PaletteContextId {
  if (pathname.startsWith('/runs')) return 'run';
  if (pathname.startsWith('/projects')) return 'project';
  return 'workflow';
}
