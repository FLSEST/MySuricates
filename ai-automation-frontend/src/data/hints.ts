/**
 * Hints in place (TODO e284eb3d): a short card that explains a page or a state the first time
 * someone meets it. MySuricates can be used in many ways, so there is no guided path — each hint
 * is rendered by the component it explains (`<Hint id>`), never from a central list. A page that
 * does not exist in a preset, in Simple Mode or with a module switched off takes its hint with it,
 * so nothing claims "not done yet" where the thing is missing (the reason the walkthrough has no
 * anchors, TODO c78fec42).
 *
 * Copy rules: say what something is for and why, never where to click — that survives layout
 * changes. The copy lives in `hints.<lang>.json`.
 *
 * `version`: raise it only when the content changes in substance. Dismissing is stored per id with
 * the version it applied to, so a raised version shows the hint again to everyone who has hints on.
 *
 * `hints.test.ts` keeps this registry, the copy in every language and the `<Hint id>` usages in step.
 */
export const HINTS = {
  'dashboard-views': { version: 1 },
  'workflows-start': { version: 1 },
  'run-steps': { version: 1 },
  'run-failed': { version: 1 },
  'approval-waiting': { version: 1 },
  'todos-sources': { version: 1 },
} as const satisfies Record<string, { version: number }>;

export type HintId = keyof typeof HINTS;

export const HINT_IDS = Object.keys(HINTS) as HintId[];
