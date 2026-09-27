import { describe, it, expect } from 'vitest';
import appSource from '../../App.tsx?raw';
import { navItems } from '../navItems';
import { HINTS } from '../../data/hints';
import { HINT_MODULES } from '../homeCommand/explanations';

/**
 * No menu entry without an explanation (TODO 1df94028): every sidebar entry names the hint its page
 * renders, and that page really renders it. The copy ships with the build, so a fresh install and
 * every update carry it; a raised hint version shows changed copy again (`data/hints.ts`).
 */

const PAGES = import.meta.glob<string>('../../pages/*.tsx', { query: '?raw', import: 'default', eager: true });

/** Component name → page file (`Docs`), from the lazy imports in App.tsx. */
function lazyPages(source: string): Map<string, string> {
  const pages = new Map<string, string>();
  for (const m of source.matchAll(/const (\w+) = lazy\(\(\) => import\('\.\/pages\/(\w+)'\)/g)) {
    pages.set(m[1]!, m[2]!);
  }
  return pages;
}

/**
 * The page files the full (non-Simple) UI renders for a sidebar route. The route's element either
 * names the page itself (inside `ModuleRoute`) or a `XxxRoute` switch whose Simple branch is left out.
 */
export function pageFilesFor(route: string, source: string = appSource): string[] {
  const pattern = route === '/'
    ? /index: true, element: (.*) \},/
    : new RegExp(`path: '${route.slice(1)}', element: (.*) \\},`);
  const element = source.match(pattern)?.[1] ?? '';
  const pages = lazyPages(source);
  const components = [...element.matchAll(/<([A-Z]\w*)/g)].map((m) => m[1]!).filter((c) => c !== 'ModuleRoute');
  const resolved = components.flatMap((component) => {
    const body = source.match(new RegExp(`function ${component}\\(\\) \\{([\\s\\S]*?)\\n\\}`))?.[1];
    return body ? [...body.matchAll(/<([A-Z]\w*)/g)].map((m) => m[1]!) : [component];
  });
  return resolved.filter((c) => !c.startsWith('Simple')).flatMap((c) => (pages.has(c) ? [pages.get(c)!] : []));
}

function renders(pageFile: string, hint: string): boolean {
  return (PAGES[`../../pages/${pageFile}.tsx`] ?? '').includes(`<Hint id="${hint}"`);
}

describe('sidebar entries and their hints', () => {
  it('finds the page of every sidebar entry – a route the resolver misses would pass unchecked', () => {
    for (const item of navItems) {
      expect(pageFilesFor(item.to), item.to).not.toEqual([]);
    }
  });

  it('names a registered hint for every sidebar entry', () => {
    for (const item of navItems) {
      expect(Object.keys(HINTS), item.to).toContain(item.hint);
    }
  });

  it('renders that hint on the page of the entry', () => {
    for (const item of navItems) {
      const files = pageFilesFor(item.to);
      expect(files.some((file) => renders(file, item.hint)), `${item.to} → ${files.join(', ')}`).toBe(true);
    }
  });

  it('notices a hint that the page does not render (counter-check)', () => {
    expect(renders('Chains', 'chains-sequence')).toBe(true);
    expect(renders('Chains', 'workflows-start')).toBe(false);
  });

  it('leaves the Simple variant out and follows the route switch to the full page', () => {
    expect(pageFilesFor('/todos')).toEqual(['Todos']);
    expect(pageFilesFor('/reports')).toEqual(['AuditCenter']);
  });

  it('takes the module of a hint from its sidebar entry', () => {
    for (const item of navItems.filter((i) => i.module)) {
      expect(HINT_MODULES[item.hint], item.to).toBe(item.module);
    }
    expect(Object.values(HINT_MODULES).filter(Boolean)).toHaveLength(navItems.filter((i) => i.module).length);
  });
});
