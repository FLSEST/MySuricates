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
 * `blueprint`: a shipped blueprint that shows the thing at work (TODO 093d2f11). The hint links to it —
 * the create dialog with that blueprint chosen — instead of describing a click path.
 *
 * `hints.test.ts` keeps this registry, the copy in every language and the `<Hint id>` usages in step.
 */
export const HINTS = {
  'dashboard-views': { version: 1 },
  // v2: the blueprint group "around workflows" (093d2f11).
  'workflows-start': { version: 2 },
  'run-steps': { version: 1 },
  'run-failed': { version: 1 },
  // v2: the run does not wait forever — the step has a time limit (30 minutes by default).
  'approval-waiting': { version: 2 },
  'todos-sources': { version: 1 },
  'projects-directory': { version: 1 },
  'project-context': { version: 1 },
  'workflow-versions': { version: 1 },
  'runs-list': { version: 1 },
  'approvals-inbox': { version: 1 },
  // v2: catching up missed times depends on a setting (on by default, off on some instances) — no default claimed.
  // v3: links the schedule blueprint (093d2f11).
  'schedules-timing': { version: 3, blueprint: 'blueprint-schedule' },
  'calendar-overview': { version: 1 },
  'cockpit-overview': { version: 1 },
  'goals-overview': { version: 1 },
  // v2: grouped by work package, workflows are dragged onto TODOs, packages or the goal (52bc1e8c).
  'goal-relation-map': { version: 2 },
  // v2: open questions are decided in place, ✔ counts what is decided (68a5e24e).
  'goal-outline': { version: 2 },
  // v2: milestones and work packages as bars in project bands, adding them with a click (c60bf914).
  'goal-timeline': { version: 2 },
  'goal-guard': { version: 1 },
  'activity-events': { version: 1 },
  'dlq-retry': { version: 1 },
  'wishes-loop': { version: 1 },
  'factory-create': { version: 1 },
  // v2: the chain template of two blueprints (093d2f11).
  // 093d2f11: hints in place for TODO triggers and workflow memory, each linking its blueprint.
  'todo-triggers-fix': { version: 1, blueprint: 'blueprint-todo-trigger' },
  'workflow-memory': { version: 1, blueprint: 'blueprint-memory' },
  'chains-sequence': { version: 2 },
  'skills-packages': { version: 1 },
  // v2: links the signed-webhook blueprint (093d2f11).
  'webhooks-trigger': { version: 2, blueprint: 'blueprint-webhook' },
  // v2: links the integration blueprint (093d2f11).
  'integrations-connect': { version: 2, blueprint: 'blueprint-integration' },
  'debug-failing': { version: 1 },
  'audit-findings': { version: 1 },
  'insights-numbers': { version: 1 },
  'onboarding-profiles': { version: 1 },
  'dialogs-sessions': { version: 1 },
  'docs-reading': { version: 1 },
  'simple-home': { version: 1 },
  'simple-result': { version: 1 },
  'simple-create': { version: 1 },
  // Only while assistant, factory and wishes are all off (preset core): the ways to build from a description.
  'build-from-description': { version: 1 },
} as const satisfies Record<string, { version: number; blueprint?: `blueprint-${string}` }>;

export type HintId = keyof typeof HINTS;

export const HINT_IDS = Object.keys(HINTS) as HintId[];

/** The blueprint a hint links to, if any. */
export function hintBlueprint(id: HintId): string | undefined {
  const entry = HINTS[id];
  return 'blueprint' in entry ? entry.blueprint : undefined;
}
