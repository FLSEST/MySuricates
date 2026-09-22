import { describe, it, expect } from 'vitest';
import de from '../../data/hints.de.json';
import en from '../../data/hints.en.json';
import { HINTS, HINT_IDS } from '../../data/hints';
import { HINT_LANGUAGES, hintText } from '../hints';

const catalogs: Record<string, Record<string, { title: string; points: string[] }>> = { de, en };

// Every source that may render a hint (tests excluded).
const SOURCES = import.meta.glob<string>(['../../**/*.tsx', '!../../**/__tests__/**'], {
  query: '?raw',
  import: 'default',
  eager: true,
});

/** Ids of every `<Hint id="…">` in the sources. */
function renderedHintIds(): Set<string> {
  const ids = new Set<string>();
  for (const source of Object.values(SOURCES)) {
    for (const match of source.matchAll(/<Hint\s+id="([^"]+)"/g)) {
      if (match[1]) ids.add(match[1]);
    }
  }
  return ids;
}

describe('hints', () => {
  it('lists every language that has a JSON file', () => {
    expect([...HINT_LANGUAGES].sort()).toEqual(Object.keys(catalogs).sort());
  });

  // Registry and copy are edited separately; a hint in one and not the other would show its id
  // as the headline, which no type check catches.
  it('has copy for exactly the registered hints, in every language', () => {
    for (const lang of HINT_LANGUAGES) {
      expect(Object.keys(catalogs[lang] ?? {}).sort(), lang).toEqual([...HINT_IDS].sort());
    }
  });

  it('has a title and at least one point for every hint', () => {
    for (const lang of HINT_LANGUAGES) {
      for (const id of HINT_IDS) {
        const text = hintText(id, lang);
        expect(text.title, `${lang}/${id}.title`).toBeTruthy();
        expect(text.points.length, `${lang}/${id}.points`).toBeGreaterThanOrEqual(1);
        for (const point of text.points) expect(point, `${lang}/${id}`).toBeTruthy();
      }
    }
  });

  it('gives every hint a positive whole version', () => {
    for (const id of HINT_IDS) {
      expect(Number.isInteger(HINTS[id].version) && HINTS[id].version > 0, id).toBe(true);
    }
  });

  // A hint lives in the component it explains. One that is registered but rendered nowhere is an
  // orphan left behind by a removed page; the type check already rejects unregistered ids.
  it('renders every registered hint somewhere, and nothing else', () => {
    expect([...renderedHintIds()].sort()).toEqual([...HINT_IDS].sort());
  });

  it('falls back to English, and to the id for an unknown hint', () => {
    expect(hintText('dashboard-views', 'fr')).toEqual(en['dashboard-views']);
    expect(hintText('no-such-hint', 'de')).toEqual({ title: 'no-such-hint', points: [] });
  });
});
