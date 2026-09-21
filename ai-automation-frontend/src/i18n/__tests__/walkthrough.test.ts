import { describe, it, expect } from 'vitest';
import de from '../../data/walkthrough.de.json';
import en from '../../data/walkthrough.en.json';
import { walkthroughText, WALKTHROUGH_LANGUAGES } from '../walkthrough';
import { WALKTHROUGH_PANELS } from '../../data/walkthroughPanels';

const catalogs: Record<string, Record<string, { title: string; points: string[] }>> = { de, en };

describe('walkthrough copy', () => {
  it('lists every language that has a JSON file', () => {
    expect([...WALKTHROUGH_LANGUAGES].sort()).toEqual(Object.keys(catalogs).sort());
  });

  it('covers the same panels in every language, both ways', () => {
    for (const a of WALKTHROUGH_LANGUAGES) {
      for (const b of WALKTHROUGH_LANGUAGES) {
        expect(Object.keys(catalogs[a] ?? {}).sort()).toEqual(Object.keys(catalogs[b] ?? {}).sort());
      }
    }
  });

  it('has a title and at least two points for every panel', () => {
    for (const lang of WALKTHROUGH_LANGUAGES) {
      for (const [id, entry] of Object.entries(catalogs[lang] ?? {})) {
        expect(entry.title, `${lang}/${id}.title`).toBeTruthy();
        expect(entry.points.length, `${lang}/${id}.points`).toBeGreaterThanOrEqual(2);
        for (const point of entry.points) expect(point, `${lang}/${id}`).toBeTruthy();
      }
    }
  });

  // The structure file and the copy are edited separately; a panel in one and not the other
  // would render its own key as the headline, which no type check catches.
  it('matches the panel list exactly, in both directions', () => {
    const structure = WALKTHROUGH_PANELS.map((p) => p.id).sort();
    for (const lang of WALKTHROUGH_LANGUAGES) {
      expect(Object.keys(catalogs[lang] ?? {}).sort(), lang).toEqual(structure);
    }
  });

  it('has no duplicate panel ids', () => {
    const ids = WALKTHROUGH_PANELS.map((p) => p.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('ends on the closing panel and marks exactly one', () => {
    expect(WALKTHROUGH_PANELS.filter((p) => p.closing)).toHaveLength(1);
    expect(WALKTHROUGH_PANELS[WALKTHROUGH_PANELS.length - 1]?.closing).toBe(true);
  });

  it('returns the copy of the requested language', () => {
    expect(walkthroughText('mix', 'de').title).not.toBe(walkthroughText('mix', 'en').title);
  });

  it('falls back to English for an unknown language', () => {
    expect(walkthroughText('mix', 'fr')).toEqual(walkthroughText('mix', 'en'));
  });

  it('falls back to the key itself for an unknown panel', () => {
    expect(walkthroughText('does-not-exist', 'de')).toEqual({ title: 'does-not-exist', points: [] });
  });
});
