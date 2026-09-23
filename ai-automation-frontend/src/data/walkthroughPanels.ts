/**
 * Order and structure of the welcome walkthrough. The copy for each id lives in
 * `walkthrough.<lang>.json`; only the shape is here.
 *
 * The panels deliberately point **nowhere into the app** — no routes, no state probes. A tour that
 * navigates would break on the `core` preset (most module pages do not exist there), in Simple Mode
 * (different routes) and with per-module switches; a state probe cannot tell "not done yet" from
 * "not available here", which makes it worse than no checklist at all.
 */
export type PanelDiagramId =
  | 'intro' | 'mix' | 'trace' | 'notice' | 'unattended' | 'wait' | 'repair'
  | 'verdict' | 'refill' | 'swap' | 'connect' | 'blocks' | 'closing';

/**
 * Modules a panel or a single point depends on. Every value here needs a `useModuleEnabled` call in
 * `Walkthrough.tsx` — the lookup there is typed as a total record over this union, so adding a value
 * without the hook does not compile.
 */
export type PanelModule = 'audit' | 'bpmn' | 'chains' | 'factory' | 'integrations';

export interface WalkthroughPanel {
  /** Key into `walkthrough.<lang>.json`. */
  id: string;
  /** Deterministic diagram drawn next to the copy; panels without one are illustration-only. */
  diagram?: PanelDiagramId;
  /** Hidden when this module is off. Only set where the panel would otherwise claim something untrue. */
  module?: PanelModule;
  /**
   * Single points that are only true with a module on, by their index in `points` — for a panel
   * whose other points hold everywhere, so the panel stays and loses just that sentence. Every
   * language keeps the same points in the same order (`walkthrough.test.ts` pins the counts).
   */
  pointModules?: Readonly<Partial<Record<number, PanelModule>>>;
  /** The last panel closes the tour instead of advancing. */
  closing?: boolean;
}

/**
 * `unattended`, `wait` and `repair` stand together on purpose and in that order: it starts on its
 * own — it holds still when it needs a person — it picks itself up when it breaks, within a ceiling
 * you set. Each alone raises the question the next one answers, so splitting them up costs more
 * than it saves.
 *
 * On the public `core` preset the tour must not mention what is missing there (f2a233eb): BPMN
 * ("process diagram") and chains are research modules, hence the point gates. `refill` needs no
 * gate — it is a pattern of core parts (a schedule, `previous_report`, shell steps), not the memory
 * module.
 */
export const WALKTHROUGH_PANELS: readonly WalkthroughPanel[] = [
  { id: 'intro', diagram: 'intro' },
  { id: 'mix', diagram: 'mix' },
  { id: 'trace', diagram: 'trace', pointModules: { 2: 'bpmn' } },
  { id: 'notice', diagram: 'notice' },
  { id: 'unattended', diagram: 'unattended' },
  { id: 'wait', diagram: 'wait' },
  // The auto-fix hangs off the factory module (`ModuleConditions.FACTORY`), which the public
  // `core` preset does not carry — without the gate this panel would promise a repair that
  // cannot happen on the instance reading it.
  { id: 'repair', diagram: 'repair', module: 'factory' },
  { id: 'verdict', diagram: 'verdict', module: 'audit' },
  { id: 'refill', diagram: 'refill' },
  { id: 'swap', diagram: 'swap' },
  { id: 'connect', diagram: 'connect', module: 'integrations', pointModules: { 1: 'bpmn' } },
  { id: 'blocks', diagram: 'blocks', pointModules: { 1: 'chains' } },
  { id: 'closing', diagram: 'closing', closing: true },
];
