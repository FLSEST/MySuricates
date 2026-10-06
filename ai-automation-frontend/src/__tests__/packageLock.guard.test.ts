import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Lockfile guard: package-lock.json mirrors the manifest in its root entry. If it deviates
// (version, engines, dependency lists), the first `npm install` of a fresh
// clone rewrites the lockfile — after scripts/setup.sh the user has a change in the tree that they
// never made, and the runtime JAR is called "-dirty". Found by the installer probe run
// (TODO 453c01b1). Fix on deviation: `npm install` in the frontend folder, commit the lockfile;
// version bumps are made by scripts/release.sh via `npm version`, which updates both files.
describe('package-lock.json guard', () => {
  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const read = (name: string) =>
    JSON.parse(readFileSync(path.resolve(currentDir, '../..', name), 'utf-8'));
  const manifest = read('package.json');
  const lock = read('package-lock.json');
  const root = lock.packages?.[''] ?? {};

  it('carries the version from package.json (top level and in the root entry)', () => {
    expect(lock.version).toBe(manifest.version);
    expect(root.version).toBe(manifest.version);
  });

  it('spiegelt engines aus package.json', () => {
    expect(root.engines).toEqual(manifest.engines);
  });

  it('mirrors dependencies and devDependencies from package.json', () => {
    expect(root.dependencies ?? {}).toEqual(manifest.dependencies ?? {});
    expect(root.devDependencies ?? {}).toEqual(manifest.devDependencies ?? {});
  });
});
