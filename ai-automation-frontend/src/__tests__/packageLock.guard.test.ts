import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Lockfile-Wächter: package-lock.json spiegelt im Wurzeleintrag das Manifest. Weicht er ab
// (Version, engines, Abhängigkeitslisten), schreibt das erste `npm install` eines frischen
// Klons den Lockfile um — der Nutzer hat nach scripts/setup.sh eine Änderung im Baum, die er
// nie gemacht hat, und das Runtime-JAR heißt "-dirty". Gefunden vom Installer-Probelauf
// (TODO 453c01b1). Behebung bei Abweichung: `npm install` im Frontend-Ordner, Lockfile committen;
// Versionssprünge macht scripts/release.sh über `npm version`, das beide Dateien zieht.
describe('package-lock.json guard', () => {
  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const read = (name: string) =>
    JSON.parse(readFileSync(path.resolve(currentDir, '../..', name), 'utf-8'));
  const manifest = read('package.json');
  const lock = read('package-lock.json');
  const root = lock.packages?.[''] ?? {};

  it('trägt die Version aus package.json (oben und im Wurzeleintrag)', () => {
    expect(lock.version).toBe(manifest.version);
    expect(root.version).toBe(manifest.version);
  });

  it('spiegelt engines aus package.json', () => {
    expect(root.engines).toEqual(manifest.engines);
  });

  it('spiegelt dependencies und devDependencies aus package.json', () => {
    expect(root.dependencies ?? {}).toEqual(manifest.dependencies ?? {});
    expect(root.devDependencies ?? {}).toEqual(manifest.devDependencies ?? {});
  });
});
