/**
 * Order and structure of the welcome walkthrough. The copy for each id lives in
 * `walkthrough.<lang>.json`; only the shape is here.
 *
 * The panels deliberately point **nowhere into the app** — no routes, no state probes. A tour that
 * navigates would break on the `core` preset (most module pages do not exist there), in Simple Mode
 * (different routes) and with per-module switches; a state probe cannot tell "not done yet" from
 * "not available here", which makes it worse than no checklist at all.
 */
export type PanelDiagramId = 'mix' | 'trace' | 'wait' | 'verdict' | 'refill';

export interface WalkthroughPanel {
  /** Key into `walkthrough.<lang>.json`. */
  id: string;
  /** Deterministic diagram drawn next to the copy; panels without one are illustration-only. */
  diagram?: PanelDiagramId;
  /** Hidden when this module is off. Only set where the panel would otherwise claim something untrue. */
  module?: 'audit';
  /** Meerkat illustration, added once the image workflow has produced the set. */
  image?: string;
  /** The last panel closes the tour instead of advancing. */
  closing?: boolean;
}

export const WALKTHROUGH_PANELS: readonly WalkthroughPanel[] = [
  { id: 'mix', diagram: 'mix' },
  { id: 'trace', diagram: 'trace' },
  { id: 'notice' },
  { id: 'wait', diagram: 'wait' },
  { id: 'verdict', diagram: 'verdict', module: 'audit' },
  { id: 'refill', diagram: 'refill' },
  { id: 'swap' },
  { id: 'blocks' },
  { id: 'closing', closing: true },
];
