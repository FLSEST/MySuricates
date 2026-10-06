import { describe, it, expect } from 'vitest';
import de from '../../data/walkthrough.de.json';
import en from '../../data/walkthrough.en.json';
import { walkthroughText, WALKTHROUGH_LANGUAGES } from '../walkthrough';
import { WALKTHROUGH_PANELS } from '../../data/walkthroughPanels';
import prompts from '../../data/walkthroughImagePrompts.json';

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

  // Without a scene in the image concept a panel would never get an illustration — and that would otherwise
  // only be noticed once the image workflow has run.
  it('has an image concept for every panel', () => {
    expect(Object.keys(prompts.szenen).sort()).toEqual(WALKTHROUGH_PANELS.map((p) => p.id).sort());
    for (const [id, szene] of Object.entries(prompts.szenen)) {
      expect(szene, id).toBeTruthy();
    }
    expect(prompts.stil).toBeTruthy();
  });

  // Point gates (`pointModules`) address a point by its index, so every language needs the same
  // points in the same order — a translation that merges two points would move a gate.
  it('keeps the same number of points per panel in every language', () => {
    for (const panel of WALKTHROUGH_PANELS) {
      const counts = WALKTHROUGH_LANGUAGES.map((lang) => catalogs[lang]?.[panel.id]?.points.length);
      expect(new Set(counts).size, panel.id).toBe(1);
    }
  });

  it('gates only points that exist, and leaves every panel two points with its gates closed', () => {
    for (const panel of WALKTHROUGH_PANELS) {
      const gated = Object.keys(panel.pointModules ?? {}).map(Number);
      for (const lang of WALKTHROUGH_LANGUAGES) {
        const points = catalogs[lang]?.[panel.id]?.points ?? [];
        for (const index of gated) {
          expect(Number.isInteger(index) && index >= 0 && index < points.length, `${lang}/${panel.id}[${index}]`).toBe(true);
        }
        expect(points.length - gated.length, `${lang}/${panel.id}`).toBeGreaterThanOrEqual(2);
      }
    }
  });

  // f2a233eb: on the public `core` preset the tour must not mention what is missing there. Every
  // panel or point that needs a module is listed here — a new claim about a module-bound feature
  // has to be bound on purpose, and removing a gate shows up as a failing line, not as a silent
  // promise on a fresh installation. `refill` stays ungated: a pattern of core parts (a schedule,
  // previous_report, shell steps), not the memory module.
  it('binds exactly these panels and points to a module', () => {
    const tags: Record<string, string> = {};
    for (const panel of WALKTHROUGH_PANELS) {
      if (panel.module) tags[panel.id] = panel.module;
      for (const [index, module] of Object.entries(panel.pointModules ?? {})) {
        if (module) tags[`${panel.id}[${index}]`] = module;
      }
    }
    expect(tags).toEqual({
      'trace[2]': 'bpmn', // "Export the whole thing as a process diagram"
      repair: 'factory',
      verdict: 'audit',
      connect: 'integrations',
      'connect[1]': 'bpmn', // "A flow leaves as a process diagram and comes back as one"
      'blocks[1]': 'chains', // "Chains line up finished flows end to end"
    });
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
