import type { Intent, ParsedQuery } from './types';

/** Lower case without diacritics and with ß → ss, so "Läufe" and "laufe" meet. */
export function normalize(text: string): string {
  return text.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '').replace(/ß/g, 'ss');
}

/** Filler words (de + en) that would match nearly everything. */
const STOP_WORDS: ReadonlySet<string> = new Set([
  // de
  'der', 'die', 'das', 'den', 'dem', 'des', 'ein', 'eine', 'einen', 'einem', 'einer', 'eines',
  'und', 'oder', 'ist', 'sind', 'war', 'was', 'wie', 'wo', 'wer', 'mit', 'von', 'zu', 'zum', 'zur',
  'im', 'in', 'am', 'an', 'auf', 'fur', 'mein', 'meine', 'meinen', 'mir', 'mich', 'ich', 'du', 'es',
  'bitte', 'zeig', 'zeige', 'mal', 'gibt', 'hat', 'haben', 'noch', 'schon', 'alle', 'alles', 'nicht',
  // en
  'the', 'a', 'an', 'and', 'or', 'is', 'are', 'was', 'were', 'what', 'how', 'where', 'who', 'with',
  'of', 'to', 'in', 'on', 'at', 'for', 'my', 'me', 'i', 'you', 'it', 'this', 'that', 'please', 'show',
  'all', 'any', 'do', 'does', 'did', 'has', 'have', 'not',
]);

/**
 * Keywords per intent. A trailing `*` matches every word starting with it ("fehl*" → "fehlgeschlagen"),
 * everything else must match the whole word. Compared after `normalize`, so without umlauts.
 */
const INTENT_KEYWORDS: Record<Exclude<Intent, 'explain'>, readonly string[]> = {
  failed: [
    'fehl*', 'schief*', 'kaputt*', 'gescheitert', 'scheiter*', 'abgebrochen',
    'fail*', 'error*', 'broken', 'crash*',
  ],
  schedule: [
    'heute', 'morgen', 'ubermorgen', 'today', 'tomorrow', 'wann', 'when',
    'zeitplan*', 'geplant*', 'plan', 'termin*', 'schedule*', 'taglich', 'wochentlich', 'daily', 'weekly',
    'montag', 'dienstag', 'mittwoch', 'donnerstag', 'freitag', 'samstag', 'sonntag',
    'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday',
  ],
  waiting: [
    'warte', 'warten', 'wartet', 'wartend*', 'freigab*', 'freigeb*', 'genehmig*',
    'approv*', 'waiting', 'wait', 'pending', 'answer*',
  ],
  todo: ['aufgabe*', 'todo*', 'task*'],
  locale: ['englisch', 'english', 'deutsch', 'german', 'sprache*', 'language*', 'locale'],
  theme: ['dunkel*', 'hell', 'heller', 'dark*', 'light', 'lighter', 'theme*', 'farbschema', 'nachtmodus'],
  settings: ['einstellung*', 'setting*', 'optionen', 'konfig*', 'hinweis*', 'hint*', 'tipp*', 'pro', 'modus', 'mode'],
  create: ['neu', 'neue*', 'anlegen', 'erstell*', 'vorlage*', 'template*', 'create', 'new', 'pool'],
};

/** Question words: the explanations answer "was ist …" without AI. */
const EXPLAIN_WORDS: readonly string[] = [
  'was', 'wie', 'warum', 'wozu', 'wofur', 'erklar*', 'hilfe', 'bedeutet',
  'what', 'how', 'why', 'explain*', 'help', 'mean*',
];

/** Relative day words → days from today. */
const DAY_WORDS: ReadonlyMap<string, number> = new Map([
  ['heute', 0], ['today', 0], ['morgen', 1], ['tomorrow', 1], ['ubermorgen', 2],
]);

/** Weekday names → JS weekday (0 = Sunday). */
const WEEKDAYS: ReadonlyMap<string, number> = new Map([
  ['sonntag', 0], ['montag', 1], ['dienstag', 2], ['mittwoch', 3], ['donnerstag', 4], ['freitag', 5], ['samstag', 6],
  ['sunday', 0], ['monday', 1], ['tuesday', 2], ['wednesday', 3], ['thursday', 4], ['friday', 5], ['saturday', 6],
]);

const TODO_PREFIX = /^(?:aufgabe|todo|task)\s*:\s*(.*)$/i;

/** Whether `word` (normalized) matches a keyword spec (`fehl*` = prefix, otherwise the whole word). */
export function matchesKeyword(word: string, spec: string): boolean {
  return spec.endsWith('*') ? word.startsWith(spec.slice(0, -1)) : word === spec;
}

function matchesAny(word: string, specs: readonly string[]): boolean {
  return specs.some((spec) => matchesKeyword(word, spec));
}

/** Normalized words of a text (letters and digits), without the one-letter ones. */
export function words(text: string): string[] {
  return normalize(text).split(/[^\p{L}\p{N}]+/u).filter((w) => w.length >= 2);
}

/**
 * Reads the command field's input: search words, intents from de/en keywords, a named day and the
 * "aufgabe:" prefix. `today` is the current weekday (0 = Sunday), for "Freitag" → days from now.
 * Pure function.
 */
export function parseHomeQuery(input: string, today: number): ParsedQuery {
  const raw = input.trim();
  const prefix = TODO_PREFIX.exec(raw);
  const todoText = prefix ? prefix[1]!.trim() : null;
  const all = words(raw);
  const explain = all.some((w) => matchesAny(w, EXPLAIN_WORDS));

  let intents: Intent[] = [];
  const add = (intent: Intent) => { if (!intents.includes(intent)) intents.push(intent); };
  if (prefix) add('todo');

  let dayOffset: number | null = null;
  let scheduleOnlyByDay = true;
  const tokens: string[] = [];
  const contentTokens: string[] = [];
  for (const word of all) {
    let keyword = false;
    for (const [intent, specs] of Object.entries(INTENT_KEYWORDS) as Array<[Intent, readonly string[]]>) {
      if (matchesAny(word, specs)) { add(intent); keyword = true; }
    }
    const relative = DAY_WORDS.get(word);
    const weekday = WEEKDAYS.get(word);
    const dayWord = relative !== undefined || weekday !== undefined;
    if (matchesAny(word, INTENT_KEYWORDS.schedule) && !dayWord) scheduleOnlyByDay = false;
    if (dayOffset === null && dayWord) {
      dayOffset = relative ?? (weekday! - today + 7) % 7;
    }
    if (STOP_WORDS.has(word)) continue;
    tokens.push(word);
    if (!keyword && !matchesAny(word, EXPLAIN_WORDS)) contentTokens.push(word);
  }
  // "Was ist heute fehlgeschlagen?" asks about failed runs of that day, not about schedules
  if (intents.includes('failed') && scheduleOnlyByDay) intents = intents.filter((i) => i !== 'schedule');
  // A question word only lifts the explanations — after anything more specific
  if (explain) add('explain');
  // The prefix itself is no search word
  if (prefix) {
    const prefixWord = normalize(raw.split(':')[0]!.trim());
    const idx = tokens.indexOf(prefixWord);
    if (idx >= 0) tokens.splice(idx, 1);
  }

  return { raw, tokens, contentTokens, intents, dayOffset, todoText };
}

/** A word without a common de/en plural or inflection ending ("workflows" → "workflow", "projekte" → "projekt"). */
export function stem(word: string): string {
  // The longest ending that fits decides; a stem shorter than four letters matches too much
  const ending = ['en', 'er', 'es', 'e', 's', 'n'].find((e) => word.endsWith(e));
  return ending && word.length - ending.length >= 4 ? word.slice(0, -ending.length) : word;
}

/** How many of `tokens` occur in `text` (normalized substring match, also without an inflection ending). */
export function countHits(tokens: readonly string[], text: string | null | undefined): number {
  if (!text || tokens.length === 0) return 0;
  const haystack = normalize(text);
  return tokens.reduce((n, tok) => (haystack.includes(tok) || haystack.includes(stem(tok)) ? n + 1 : n), 0);
}

/**
 * The one word to hand a server-side substring search (the TODO list's `q`): the longest content
 * word, as typed (with its umlauts). Falls back to the longest search word; null when there is none.
 */
export function serverSearchTerm(query: ParsedQuery): string | null {
  const pick = (candidates: readonly string[]) => {
    const typed = query.raw.split(/[^\p{L}\p{N}]+/u).filter((w) => candidates.includes(normalize(w)));
    return typed.sort((a, b) => b.length - a.length)[0] ?? null;
  };
  return pick(query.contentTokens) ?? pick(query.tokens);
}
