import { describe, it, expect } from 'vitest';
import de from '../../data/modules.de.json';
import en from '../../data/modules.en.json';
import { moduleText, moduleName, MODULE_TEXT_LANGUAGES } from '../modules';

const catalogs: Record<string, Record<string, { name: string; description: string }>> = { de, en };

describe('module texts', () => {
  it('lists every language that has a JSON file', () => {
    expect([...MODULE_TEXT_LANGUAGES].sort()).toEqual(Object.keys(catalogs).sort());
  });

  it('covers the same keys in every language, both ways', () => {
    for (const a of MODULE_TEXT_LANGUAGES) {
      for (const b of MODULE_TEXT_LANGUAGES) {
        expect(Object.keys(catalogs[a] ?? {}).sort()).toEqual(Object.keys(catalogs[b] ?? {}).sort());
      }
    }
  });

  it('has a name and a description for every key', () => {
    for (const lang of MODULE_TEXT_LANGUAGES) {
      for (const [key, entry] of Object.entries(catalogs[lang] ?? {})) {
        expect(entry.name, `${lang}/${key}.name`).toBeTruthy();
        expect(entry.description, `${lang}/${key}.description`).toBeTruthy();
      }
    }
  });

  it('returns the text of the requested language', () => {
    expect(moduleName('remote-access', 'de')).toBe('Fernzugriff');
    expect(moduleName('remote-access', 'en')).toBe('Remote access');
    expect(moduleText('memory', 'de').description).not.toBe(moduleText('memory', 'en').description);
  });

  it('falls back to English for an unknown language', () => {
    expect(moduleText('remote-access', 'fr')).toEqual(moduleText('remote-access', 'en'));
  });

  it('falls back to the key itself for an unknown module', () => {
    expect(moduleName('does-not-exist', 'de')).toBe('does-not-exist');
    expect(moduleText('does-not-exist', 'de').description).toBe('');
  });
});
