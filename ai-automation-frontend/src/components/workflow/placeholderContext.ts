import type { BlockDefinition } from '../../types';
import type { SchemaLocale } from './blockConfigSchema';

/**
 * Which `{{ … }}` placeholders are bound at a given position of a block list, and where each value comes
 * from — the editor uses it for suggestions after `{{`, the hover, the `← #2 backend_tests` hint and the
 * warnings. Mirrors the runtime: BlockStepRunner records every block output as `previous_output`,
 * `block.<name>.output` and `step_<index>_output`; blocks with `variable_name` set context variables;
 * ExecutionContextFactory injects the system variables. The backend's DataFlowReferenceScanner draws
 * the same edges in the diagram.
 */

export type PlaceholderKind = 'output' | 'variable' | 'system' | 'input';

export interface PlaceholderInfo {
  key: string;
  kind: PlaceholderKind;
  /** Markdown explanation for suggestion details and hover. */
  doc: string;
  /** Short origin shown behind the placeholder, e.g. `#2 backend_tests`. */
  source?: string;
  /** Value a run may carry from outside (block output, platform data): single-quoted in shell blocks. */
  untrusted?: boolean;
}

export interface PlaceholderContext {
  items: PlaceholderInfo[];
  /** Names of blocks after the edited one — a reference to them is a mistake of order, not a typo. */
  laterNames: string[];
  /** Step indexes that do not map to a block (a wrapper before them runs one step per item). */
  ambiguousSteps: boolean;
  blockIndex: number;
  /** The edited block runs its fields as shell commands. */
  shell: boolean;
  locale: SchemaLocale;
}

type Pick2 = (de: string, en: string) => string;

/** Wrapper blocks that record one runtime step per item/iteration/attempt (StepIndexCounter). */
const MULTI_STEP_TYPES = new Set(['foreach', 'while', 'retry']);
const SHELL_TYPES = new Set(['shell']);
const MODE_TYPES = new Set(['retry', 'foreach', 'while', 'conditional', 'switch']);

function label(block: BlockDefinition, index: number): string {
  return `#${index + 1} ${block.name?.trim() || block.type}`;
}

function str(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

/** Variables a block sets for the blocks after it (its `variable_name` and fixed result keys). */
function variablesOf(block: BlockDefinition, source: string, t: Pick2): PlaceholderInfo[] {
  const cfg = block.config ?? {};
  const v = (key: string, doc: string): PlaceholderInfo => ({ key, kind: 'variable', source, doc });
  switch (block.type) {
    case 'approval':
      return [v(str(cfg.variable_name) ?? 'approval_answer', t('Antwort der Freigabe', 'Answer to the approval'))];
    case 'human-input': {
      const fields = Array.isArray(cfg.fields) ? cfg.fields : [];
      const named = fields.flatMap((f) => {
        const name = str((f as Record<string, unknown>)?.variable_name);
        return name ? [v(name, t('Formularfeld der Benutzereingabe', 'Form field of the user input'))] : [];
      });
      return [v(str(cfg.variable_name) ?? 'user_input', t('Eingabe des Benutzers', 'Input of the user')), ...named];
    }
    case 'document': {
      const name = str(cfg.variable_name);
      return name ? [v(name, t('Aus dem Dokument extrahierter Text', 'Text extracted from the document'))] : [];
    }
    case 'todo-create':
      return [v(str(cfg.variable_name) ?? 'todo_id', t('ID des angelegten TODOs', 'ID of the created TODO'))];
    case 'foreach':
      return [v(str(cfg.variable_name) ?? 'item', t('Letztes Item der Schleife', 'Last item of the loop'))];
    case 'agentic':
      return [
        v('agentic_goal_achieved', t('`true`, wenn der Agent sein Ziel erreicht hat', '`true` if the agent reached its goal')),
        v('agentic_iterations', t('Anzahl der Runden des Agenten', 'Number of agent rounds')),
      ];
    default:
      return [];
  }
}

function systemVariables(t: Pick2): PlaceholderInfo[] {
  const s = (key: string, doc: string, untrusted = false): PlaceholderInfo => ({ key, kind: 'system', doc, untrusted });
  return [
    s('workflow_run_id', t('Eindeutige ID dieses Runs', 'Unique ID of this run')),
    s('workflow_id', t('ID des Workflows', 'ID of the workflow')),
    s('workflow_name', t('Name des Workflows', 'Name of the workflow')),
    s('project_id', t('ID des Projekts (oder leer)', 'ID of the project (or empty)')),
    s('project_path', t('Pfad des Projekts', 'Path of the project')),
    s('execution_path', t('Arbeitsverzeichnis des Runs (bei isoliertem Workspace der Klon)', 'Working directory of the run (the clone with an isolated workspace)')),
    s('run_timestamp', t('Zeitstempel für Pfade, z. B. `20260922-213045`', 'Path-safe timestamp, e.g. `20260922-213045`')),
    s('started_at', t('Startzeit (ISO-8601)', 'Start time (ISO-8601)')),
    s('trigger_source', t('Auslöser: `MANUAL`, `SCHEDULE`, `WEBHOOK`, …', 'Trigger: `MANUAL`, `SCHEDULE`, `WEBHOOK`, …')),
    s('model', t('Konfiguriertes Modell (oder leer)', 'Configured model (or empty)')),
    s('provider', t('Konfigurierter KI-Anbieter (oder leer)', 'Configured AI provider (or empty)')),
    s('effort', t('Konfigurierter Denkaufwand (oder leer)', 'Configured reasoning effort (or empty)')),
    s('home_dir', t('Home-Verzeichnis', 'Home directory')),
    s('user', t('Benutzername des Betriebssystems', 'Operating system user name')),
    s('workflow_version', t('Versionsnummer des Workflows', 'Version number of the workflow')),
    s('workflow_changed_since_last_run', t('`true`, wenn sich die Definition seit dem letzten erfolgreichen Run geändert hat', '`true` if the definition changed since the last successful run')),
    s('previous_report', t('Längste KI-Ausgabe des letzten erfolgreichen Runs (max. 3000 Zeichen). In Shell-Blöcken ohne Quotes schreiben.', 'Longest AI output of the last successful run (max. 3000 chars). Write it unquoted in shell blocks.'), true),
    s('open_todos', t('Offene TODOs des Projekts als Markdown-Liste. In Shell-Blöcken ohne Quotes schreiben.', 'Open TODOs of the project as a Markdown list. Write it unquoted in shell blocks.'), true),
    s('previous_chain_output', t('Nur in einer Chain belegt: Ausgabe des vorherigen Chain-Eintrags. Einzeln gestartet bleibt der Platzhalter wörtlich stehen.', 'Only set inside a chain: output of the previous chain entry. Started on its own the placeholder stays literally.'), true),
    s('chain_run_id', t('Nur in einer Chain belegt: ID des Chain-Runs.', 'Only set inside a chain: ID of the chain run.')),
  ];
}

/** Every `{{ key }}` used anywhere in the definition, for start values the run must be given. */
function usedKeys(blocks: BlockDefinition[]): Set<string> {
  const keys = new Set<string>();
  const walk = (value: unknown) => {
    if (typeof value === 'string') for (const m of value.matchAll(PLACEHOLDER)) keys.add(m[2]!);
    else if (Array.isArray(value)) value.forEach(walk);
    else if (value && typeof value === 'object') Object.values(value).forEach(walk);
  };
  blocks.forEach((b) => walk(b.config));
  return keys;
}

/** Same syntax as the backend (PlaceholderSyntax): one optional space, the same on both sides. */
export const PLACEHOLDER = /\{\{( ?)([^{}]+?)\1\}\}/g;
/** Anything between braces, to catch spacing the backend would not resolve. */
const LOOSE_PLACEHOLDER = /\{\{([^{}]*)\}\}/g;

export function buildPlaceholderContext(
  blocks: BlockDefinition[],
  blockIndex: number,
  current: { type: string; configJson: string },
  options: { defaultContext?: Record<string, unknown>; locale: SchemaLocale },
): PlaceholderContext {
  const t: Pick2 = (de, en) => (options.locale === 'de' ? de : en);
  const before = blocks.slice(0, blockIndex);
  const items: PlaceholderInfo[] = [];
  const add = (info: PlaceholderInfo) => {
    const i = items.findIndex((it) => it.key === info.key);
    if (i >= 0) items.splice(i, 1); // the later writer wins at runtime
    items.push(info);
  };

  const prev = before.at(-1);
  add({
    key: 'previous_output',
    kind: 'output',
    untrusted: true,
    source: prev ? label(prev, blockIndex - 1) : undefined,
    doc: prev
      ? t(
        `Ausgabe des vorherigen Blocks **${label(prev, blockIndex - 1)}** (${prev.type}).`
          + (current.type === 'switch' ? ' In den Blöcken eines Case: Ausgabe des Case-Blocks davor.' : ''),
        `Output of the previous block **${label(prev, blockIndex - 1)}** (${prev.type}).`
          + (current.type === 'switch' ? ' Inside a case: output of the case block before.' : ''),
      )
      : t('Erster Block: leer, außer der Run bekommt beim Start `previous_output` übergeben.', 'First block: empty unless the run is started with `previous_output`.'),
  });

  let multiStep = false;
  before.forEach((block, i) => {
    const source = label(block, i);
    if (block.name?.trim()) {
      add({ key: `block.${block.name.trim()}.output`, kind: 'output', untrusted: true, source, doc: t(`Ausgabe von Block **${source}** (${block.type}).`, `Output of block **${source}** (${block.type}).`) });
    }
    // Named case blocks of a switch are addressable too (CompressionSources, TemplateResolver)
    if (block.type === 'switch') {
      const cases = Array.isArray(block.config?.cases) ? block.config.cases : [];
      const nested = [...cases.flatMap((c) => (Array.isArray((c as Record<string, unknown>)?.blocks) ? (c as { blocks: BlockDefinition[] }).blocks : [])),
        ...(Array.isArray(block.config?.default) ? (block.config.default as BlockDefinition[]) : [])];
      for (const inner of nested) {
        const name = str(inner?.name);
        if (name) add({ key: `block.${name}.output`, kind: 'output', untrusted: true, source: `${source} › ${name}`, doc: t(`Ausgabe des Case-Blocks **${name}** in ${source} — nur belegt, wenn dieser Case lief.`, `Output of case block **${name}** in ${source} — only set if that case ran.`) });
      }
    }
    if (!multiStep && !MULTI_STEP_TYPES.has(block.type)) {
      add({ key: `step_${i}_output`, kind: 'output', untrusted: true, source, doc: t(`Ausgabe von Schritt ${i}, also Block **${source}**. Robuster: den Block benennen und \`block.<name>.output\` nutzen.`, `Output of step ${i}, i.e. block **${source}**. More robust: name the block and use \`block.<name>.output\`.`) });
    }
    if (MULTI_STEP_TYPES.has(block.type)) multiStep = true;
    variablesOf(block, source, t).forEach(add);
  });

  // The edited foreach binds its own loop variable inside its command
  if (current.type === 'foreach') {
    let loopVar = 'item';
    try { loopVar = str((JSON.parse(current.configJson) as Record<string, unknown>).variable_name) ?? 'item'; } catch { /* half-typed */ }
    add({ key: loopVar, kind: 'variable', untrusted: true, source: t('Schleife', 'loop'), doc: t('Aktuelles Item dieser Schleife.', 'Current item of this loop.') });
  }

  systemVariables(t).forEach((info) => { if (!items.some((it) => it.key === info.key)) items.push(info); });

  const known = new Set(items.map((it) => it.key));
  const defaults = options.defaultContext ?? {};
  for (const key of Object.keys(defaults)) {
    if (!known.has(key)) {
      items.push({ key, kind: 'input', doc: t(`Startwert des Workflows (Standard: \`${String(defaults[key])}\`).`, `Workflow start value (default: \`${String(defaults[key])}\`).`) });
      known.add(key);
    }
  }
  for (const key of usedKeys(blocks)) {
    if (!known.has(key) && !key.startsWith('block.') && !/^step_\d+_output$/.test(key)) {
      items.push({ key, kind: 'input', doc: t('Startwert: wird beim Ausführen abgefragt. Ohne Wert bleibt der Platzhalter wörtlich stehen.', 'Start value: asked for when running. Without a value the placeholder stays literally.') });
      known.add(key);
    }
  }

  let shell = SHELL_TYPES.has(current.type);
  if (MODE_TYPES.has(current.type)) {
    try { shell = (JSON.parse(current.configJson) as Record<string, unknown>).execution_mode === 'shell'; } catch { /* half-typed */ }
  }

  return {
    items,
    laterNames: blocks.slice(blockIndex + 1).map((b) => b.name?.trim() ?? '').filter(Boolean),
    ambiguousSteps: multiStep,
    blockIndex,
    shell,
    locale: options.locale,
  };
}

export interface PlaceholderMark {
  /** Offsets of the whole `{{ … }}` in the text. */
  start: number;
  end: number;
  key: string;
  info?: PlaceholderInfo;
  /** Hint shown right after the placeholder. */
  hint?: string;
  problem?: { severity: 'warning' | 'info'; message: string };
}

const OUTPUT_KEY = /^(previous_output|block\..+\.output|step_\d+_output)$/;

/** Analyse every placeholder in the editor text against the context. */
export function checkPlaceholders(text: string, ctx: PlaceholderContext): PlaceholderMark[] {
  const t: Pick2 = (de, en) => (ctx.locale === 'de' ? de : en);
  const byKey = new Map(ctx.items.map((it) => [it.key, it]));
  const escapeOn = /"shell_escape_variables"\s*:\s*true/.test(text);
  const marks: PlaceholderMark[] = [];

  for (const m of text.matchAll(LOOSE_PLACEHOLDER)) {
    const start = m.index!;
    const end = start + m[0].length;
    const exact = new RegExp(PLACEHOLDER.source).exec(m[0]);
    const key = (exact ? exact[2]! : m[1]!).trim();
    const mark: PlaceholderMark = { start, end, key, info: byKey.get(key) };

    if (!exact || exact[2] !== key) {
      mark.problem = { severity: 'warning', message: t(
        `Wird nicht aufgelöst: Leerzeichen nur einfach und auf beiden Seiten gleich — \`{{ ${key} }}\` oder \`{{${key}}}\`.`,
        `Will not resolve: use one space on both sides or none — \`{{ ${key} }}\` or \`{{${key}}}\`.`) };
      marks.push(mark);
      continue;
    }

    if (mark.info?.source) mark.hint = `← ${mark.info.source}`;

    const blockRef = /^block\.(.+)\.output$/.exec(key);
    const stepRef = /^step_(\d+)_output$/.exec(key);
    if (blockRef && !mark.info) {
      mark.problem = ctx.laterNames.includes(blockRef[1]!)
        ? { severity: 'warning', message: t(`Block \`${blockRef[1]}\` läuft erst nach diesem Block — der Platzhalter bleibt wörtlich stehen.`, `Block \`${blockRef[1]}\` runs after this block — the placeholder stays literally.`) }
        : { severity: 'warning', message: t(`Keinen Block \`${blockRef[1]}\` vor diesem Block gefunden — der Platzhalter bleibt wörtlich stehen.`, `No block \`${blockRef[1]}\` before this block — the placeholder stays literally.`) };
    } else if (stepRef && !mark.info) {
      mark.problem = Number(stepRef[1]) >= ctx.blockIndex && !ctx.ambiguousSteps
        ? { severity: 'warning', message: t(`Schritt ${stepRef[1]} läuft erst nach diesem Block.`, `Step ${stepRef[1]} runs after this block.`) }
        : { severity: 'info', message: t('Schrittnummer nicht eindeutig: eine Schleife davor erzeugt mehrere Schritte. Besser den Block benennen und `block.<name>.output` nutzen.', 'Step number is ambiguous: a loop before creates several steps. Better name the block and use `block.<name>.output`.') };
    } else if (key === 'previous_output' && ctx.blockIndex === 0) {
      mark.problem = { severity: 'info', message: t('Im ersten Block ist `previous_output` leer, außer der Run bekommt es beim Start übergeben.', 'In the first block `previous_output` is empty unless the run is started with it.') };
    } else if (ctx.shell && (OUTPUT_KEY.test(key) || mark.info?.untrusted)) {
      // `'` or an escaped `\"` is a shell quote; a bare `"` is where the JSON string begins
      const quoted = /('|\\")$/.test(text.slice(Math.max(0, start - 2), start));
      if (quoted) {
        mark.problem = { severity: 'warning', message: t(
          'Ausgabe-Platzhalter in Shell-Befehlen nicht in Quotes setzen: das Escaping wirkt sonst nicht. Ohne Quotes schreiben und `"shell_escape_variables": true` setzen.',
          'Do not quote output placeholders in shell commands: escaping has no effect then. Write it unquoted and set `"shell_escape_variables": true`.') };
      } else if (!escapeOn && mark.info?.kind !== 'system') {
        // previous_report/open_todos are always escaped by the backend — only derived values need the option
        mark.problem = { severity: 'info', message: t(
          'Ohne `"shell_escape_variables": true` kommt die Ausgabe roh in den Befehl — mehrzeiliger Text zerlegt ihn. Sicher: `set -- {{ … }}` plus Escape-Option.',
          'Without `"shell_escape_variables": true` the output lands raw in the command — multi-line text breaks it. Safe: `set -- {{ … }}` plus the escape option.') };
      }
    }
    marks.push(mark);
  }
  return marks;
}
