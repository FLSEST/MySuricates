import { describe, it, expect } from 'vitest';
import { SIMPLE_MODE_ROUTES, simpleRouteKind } from './simpleRoutes';

describe('simpleRouteKind', () => {
  it.each([
    ['/', 'simple'],
    ['/workflows', 'simple'],
    ['/runs', 'simple'],
    ['/projects', 'simple'],
    ['/schedules', 'simple'],
    ['/todos', 'simple'],
    ['/activity', 'simple'],
    ['/workflows/abc-123', 'simple'],
    ['/workflows/abc-123/', 'simple'],
    ['/workflows/abc-123/run/run-9', 'simple'],
    ['/runs/run-9', 'simple'],
    ['/approvals', 'shared'],
    ['/settings', 'shared'],
    ['/docs', 'shared'],
    ['/projects/p-1', 'pro'],
    ['/factory', 'pro'],
    ['/integrations', 'pro'],
    ['/reports', 'pro'],
    ['/wishes', 'pro'],
    ['/goals/g-1', 'pro'],
    ['/chains', 'pro'],
    ['/chains/c-1/run/r-1', 'pro'],
    ['/skills', 'pro'],
    ['/calendar', 'pro'],
    ['/cockpit', 'pro'],
    ['/dlq', 'pro'],
    ['/webhooks', 'pro'],
    ['/debug', 'pro'],
    ['/workflows/abc/run', 'pro'],
    ['/settingsx', 'pro'],
  ] as const)('%s → %s', (path, kind) => {
    expect(simpleRouteKind(path)).toBe(kind);
  });

  it('classifies every sidebar route of Simple mode as simple', () => {
    for (const route of SIMPLE_MODE_ROUTES) expect(simpleRouteKind(route)).toBe('simple');
  });
});
