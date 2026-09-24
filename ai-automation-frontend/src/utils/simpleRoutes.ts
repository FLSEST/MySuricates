/** The pages the Simple-mode sidebar lists — each renders its own Simple variant. */
export const SIMPLE_MODE_ROUTES: ReadonlySet<string> = new Set([
  '/', '/workflows', '/runs', '/projects', '/schedules', '/todos', '/activity',
]);

/** Pages both modes use as they are (no Simple variant, but nothing to build either). */
const SHARED_ROUTES: ReadonlySet<string> = new Set(['/approvals', '/settings', '/docs']);

/** Deeper pages that render a Simple variant or redirect to one. */
const SIMPLE_DEEP_ROUTES: readonly RegExp[] = [
  /^\/workflows\/[^/]+$/,
  /^\/workflows\/[^/]+\/run\/[^/]+$/,
  /^\/runs\/[^/]+$/,
];

export type SimpleRouteKind = 'simple' | 'shared' | 'pro';

/**
 * Which mode a page belongs to: `simple` has a Simple variant, `shared` works in both modes,
 * `pro` is a Pro page (building/configuring) — in Simple mode it gets a visible hand-off notice.
 * Pure function.
 */
export function simpleRouteKind(pathname: string): SimpleRouteKind {
  const path = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname;
  if (SIMPLE_MODE_ROUTES.has(path) || SIMPLE_DEEP_ROUTES.some((re) => re.test(path))) return 'simple';
  if (SHARED_ROUTES.has(path)) return 'shared';
  return 'pro';
}
