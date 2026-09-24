import { describe, it, expect } from 'vitest';
import { countHits, matchesKeyword, normalize, parseHomeQuery, serverSearchTerm, stem } from './query';

const WEDNESDAY = 3;
const parse = (q: string) => parseHomeQuery(q, WEDNESDAY);

describe('normalize', () => {
  it('folds case, umlauts and ß', () => {
    expect(normalize('Läufe Größe ÜBER')).toBe('laufe grosse uber');
  });
});

describe('matchesKeyword', () => {
  it('reads a trailing * as prefix, otherwise the whole word', () => {
    expect(matchesKeyword('fehlgeschlagen', 'fehl*')).toBe(true);
    expect(matchesKeyword('hello', 'hell')).toBe(false);
    expect(matchesKeyword('hell', 'hell')).toBe(true);
  });
});

describe('parseHomeQuery — intents (de + en)', () => {
  it.each([
    ['Was ist fehlgeschlagen?', 'failed'],
    ['ist was schiefgelaufen', 'failed'],
    ['failed runs', 'failed'],
    ['error', 'failed'],
    ['was läuft heute', 'schedule'],
    ['morgen', 'schedule'],
    ['what runs tomorrow', 'schedule'],
    ['Freitag', 'schedule'],
    ['sunday', 'schedule'],
    ['was wartet auf mich', 'waiting'],
    ['Freigabe', 'waiting'],
    ['approvals', 'waiting'],
    ['waiting', 'waiting'],
    ['englisch', 'locale'],
    ['switch language', 'locale'],
    ['dunkel', 'theme'],
    ['light mode', 'theme'],
    ['neue Vorlage', 'create'],
    ['Einstellungen', 'settings'],
  ])('%s → %s', (input, intent) => {
    expect(parse(input).intents).toContain(intent);
  });

  it('keeps keywords out of the content words but in the search words', () => {
    const q = parse('backup fehlgeschlagen');
    expect(q.intents).toEqual(['failed']);
    expect(q.contentTokens).toEqual(['backup']);
    expect(q.tokens).toEqual(['backup', 'fehlgeschlagen']);
  });

  it('drops stop words and single letters', () => {
    const q = parse('Was ist ein Projekt?');
    expect(q.tokens).toEqual(['projekt']);
    expect(q.intents).toEqual(['explain']);
  });

  it('ranks a question word after the specific intent', () => {
    expect(parse('warum ist das backup fehlgeschlagen').intents).toEqual(['failed', 'explain']);
  });

  it('reads "heute" in a failure question as a day, not as a schedule question', () => {
    const q = parse('what failed today?');
    expect(q.intents).toEqual(['failed', 'explain']);
    expect(q.dayOffset).toBe(0);
  });

  it('keeps an explicit schedule word next to a failure', () => {
    expect(parse('zeitplan fehlgeschlagen').intents).toEqual(expect.arrayContaining(['failed', 'schedule']));
  });

  it('turns day words into days from today', () => {
    expect(parse('heute').dayOffset).toBe(0);
    expect(parse('tomorrow').dayOffset).toBe(1);
    expect(parse('übermorgen').dayOffset).toBe(2);
    expect(parse('Freitag').dayOffset).toBe(2); // Wednesday → Friday
    expect(parse('Montag').dayOffset).toBe(5);
    expect(parse('Mittwoch').dayOffset).toBe(0);
    expect(parse('backup').dayOffset).toBeNull();
  });

  it('ignores object-prototype words', () => {
    expect(parse('constructor').dayOffset).toBeNull();
  });

  it('reads the task prefix', () => {
    expect(parse('Aufgabe: Steuern machen')).toMatchObject({ todoText: 'Steuern machen', intents: ['todo'] });
    expect(parse('todo:call Anna').todoText).toBe('call Anna');
    expect(parse('task: ').todoText).toBe('');
    expect(parse('Aufgaben ansehen').todoText).toBeNull();
    expect(parse('Aufgaben ansehen').intents).toContain('todo');
  });

  it('is empty for blank input', () => {
    expect(parse('   ')).toMatchObject({ raw: '', tokens: [], intents: [], todoText: null, dayOffset: null });
  });
});

describe('stem and countHits', () => {
  it('strips one common ending but keeps four letters', () => {
    expect(stem('workflows')).toBe('workflow');
    expect(stem('projekte')).toBe('projekt');
    expect(stem('daten')).toBe('daten');
  });

  it('counts tokens found in the text, also by stem and without umlauts', () => {
    expect(countHits(['projekte', 'lauf'], 'Ein Projekt ist ein Ordner. Jeder Lauf …')).toBe(2);
    expect(countHits(['laufe'], 'Alle Läufe')).toBe(1);
    expect(countHits(['x'], null)).toBe(0);
  });
});

describe('serverSearchTerm', () => {
  it('picks the longest content word as typed', () => {
    expect(serverSearchTerm(parse('Rechnung prüfen fehlgeschlagen'))).toBe('Rechnung');
    expect(serverSearchTerm(parse('Steuererklärung'))).toBe('Steuererklärung');
  });

  it('falls back to a keyword and is null without words', () => {
    expect(serverSearchTerm(parse('heute'))).toBe('heute');
    expect(serverSearchTerm(parse('was ist'))).toBeNull();
  });
});

describe('parseHomeQuery — filler words', () => {
  it('drops verbs and time fillers that say nothing about what to find', () => {
    const q = parseHomeQuery('was lief in letzter Zeit schief', 3);
    expect(q.tokens).toEqual(['schief']);
    expect(q.intents).toContain('failed');
    expect(parseHomeQuery('what went wrong recently', 3).tokens).toEqual(['wrong']);
    expect(parseHomeQuery('Welche meiner Workflows sind in letzter Zeit fehlgeschlagen?', 3).tokens).toEqual(['fehlgeschlagen']);
  });
});
