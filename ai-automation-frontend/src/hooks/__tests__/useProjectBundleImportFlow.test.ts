import { describe, it, expect } from 'vitest';
import type { BundlePathToken } from '../../types';
import { detectSharedRootPath } from '../useProjectBundleImportFlow';

function path(token: string, original: string): BundlePathToken {
  return { token, original, occurrences: 1, hint: '' };
}

describe('detectSharedRootPath', () => {
  it('finds the shortest original that another original is a subpath of', () => {
    const paths = [path('@@path:1@@', '/tmp/widgetco'), path('@@path:2@@', '/tmp/widgetco/venv')];
    expect(detectSharedRootPath(paths)).toBe('/tmp/widgetco');
  });

  it('finds the top-most root through a chain of nested subpaths', () => {
    const paths = [
      path('@@path:1@@', '/home/x/project/src/deep'),
      path('@@path:2@@', '/home/x/project/src'),
      path('@@path:3@@', '/home/x/project'),
    ];
    expect(detectSharedRootPath(paths)).toBe('/home/x/project');
  });

  it('respects the path-segment boundary — a sibling directory with a shared prefix is not a subpath', () => {
    // /tmp/widgetco-qa-venv must not count as a subpath of /tmp/widgetco (no '/' boundary between them).
    const paths = [path('@@path:1@@', '/tmp/widgetco'), path('@@path:2@@', '/tmp/widgetco-qa-venv')];
    expect(detectSharedRootPath(paths)).toBeNull();
  });

  it('returns null when no original has another as a subpath', () => {
    const paths = [path('@@path:1@@', '/tmp/a'), path('@@path:2@@', '/tmp/b')];
    expect(detectSharedRootPath(paths)).toBeNull();
  });

  it('returns null when two unrelated roots each qualify and tie for shortest — refuses to guess', () => {
    const paths = [
      path('@@path:1@@', '/srv/data'), path('@@path:2@@', '/srv/data/x'),
      path('@@path:3@@', '/opt/logs'), path('@@path:4@@', '/opt/logs/y'),
    ];
    expect(detectSharedRootPath(paths)).toBeNull();
  });

  it('returns null for an empty or single-entry path list', () => {
    expect(detectSharedRootPath([])).toBeNull();
    expect(detectSharedRootPath([path('@@path:1@@', '/tmp/widgetco')])).toBeNull();
  });
});
