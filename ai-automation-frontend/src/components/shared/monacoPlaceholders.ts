import { checkPlaceholders, type PlaceholderContext, type PlaceholderKind } from '../../utils/placeholderContext';

/**
 * Monaco side of the `{{ … }}` help: suggestions after `{{`, a hover with origin and explanation, a
 * `← #2 backend_tests` inlay hint behind each placeholder and markers for references into nothing.
 * The providers are registered once for JSON; each editor model brings its own context, so the add and
 * edit dialogs (and any other JSON editor without a context) never see each other's variables.
 */

/* Loosely typed on purpose, like MonacoEditor.tsx — the app does not import Monaco's types. */
type Model = {
  uri: { toString: () => string };
  getValue: () => string;
  getPositionAt: (offset: number) => { lineNumber: number; column: number };
  getOffsetAt: (position: { lineNumber: number; column: number }) => number;
  getLineContent: (line: number) => string;
  onDidChangeContent: (listener: () => void) => { dispose: () => void };
  isDisposed?: () => boolean;
};
type Position = { lineNumber: number; column: number };
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Monaco = any;

const contexts = new Map<string, PlaceholderContext>();
let installed = false;
let hintsChanged: { fire: (value: void) => void; event: unknown } | null = null;

const MARKER_OWNER = 'placeholders';
const SEVERITY = { warning: 4, info: 2 } as const;

function rangeOf(model: Model, start: number, end: number) {
  const a = model.getPositionAt(start);
  const b = model.getPositionAt(end);
  return { startLineNumber: a.lineNumber, startColumn: a.column, endLineNumber: b.lineNumber, endColumn: b.column };
}

function kindOf(monaco: Monaco, kind: PlaceholderKind): number {
  const k = monaco.languages.CompletionItemKind;
  return { output: k.Reference, variable: k.Variable, system: k.Constant, input: k.Field }[kind];
}

const KIND_ORDER: Record<PlaceholderKind, number> = { output: 0, variable: 1, input: 2, system: 3 };

function install(monaco: Monaco) {
  if (installed) return;
  installed = true;
  hintsChanged = new monaco.Emitter();

  monaco.languages.registerCompletionItemProvider('json', {
    triggerCharacters: ['{', ' ', '.', '_'],
    provideCompletionItems(model: Model, position: Position) {
      const ctx = contexts.get(model.uri.toString());
      if (!ctx) return { suggestions: [] };
      const before = model.getLineContent(position.lineNumber).slice(0, position.column - 1);
      const open = /\{\{( ?)([\w.-]*)$/.exec(before);
      if (!open) return { suggestions: [] };
      const typed = open[2]!;
      const after = model.getLineContent(position.lineNumber).slice(position.column - 1);
      const closed = /^[\w.-]* ?\}\}/.test(after);
      const range = {
        startLineNumber: position.lineNumber, endLineNumber: position.lineNumber,
        startColumn: position.column - typed.length,
        endColumn: closed ? position.column + /^[\w.-]*/.exec(after)![0].length : position.column,
      };
      return {
        suggestions: ctx.items.map((info) => ({
          label: { label: info.key, description: info.source },
          kind: kindOf(monaco, info.kind),
          insertText: closed ? info.key : `${info.key}${open[1]}}}`,
          filterText: info.key,
          sortText: `${KIND_ORDER[info.kind]}-${info.key}`,
          documentation: { value: info.doc },
          range,
        })),
      };
    },
  });

  monaco.languages.registerHoverProvider('json', {
    provideHover(model: Model, position: Position) {
      const ctx = contexts.get(model.uri.toString());
      if (!ctx) return null;
      const offset = model.getOffsetAt(position);
      const mark = checkPlaceholders(model.getValue(), ctx).find((m) => m.start <= offset && offset <= m.end);
      if (!mark) return null;
      const head = `\`{{ ${mark.key} }}\`${mark.info?.source ? ` ← **${mark.info.source}**` : ''}`;
      const contents = [{ value: head }];
      if (mark.info) contents.push({ value: mark.info.doc });
      if (mark.problem) contents.push({ value: `⚠ ${mark.problem.message}` });
      return { range: rangeOf(model, mark.start, mark.end), contents };
    },
  });

  monaco.languages.registerInlayHintsProvider('json', {
    onDidChangeInlayHints: hintsChanged!.event,
    provideInlayHints(model: Model) {
      const ctx = contexts.get(model.uri.toString());
      if (!ctx) return { hints: [], dispose: () => {} };
      const hints = checkPlaceholders(model.getValue(), ctx)
        .filter((m) => m.hint)
        .map((m) => ({ position: model.getPositionAt(m.end), label: m.hint!, paddingLeft: true, kind: monaco.languages.InlayHintKind.Type }));
      return { hints, dispose: () => {} };
    },
  });
}

function validate(monaco: Monaco, model: Model) {
  if (model.isDisposed?.()) return;
  const ctx = contexts.get(model.uri.toString());
  const marks = ctx ? checkPlaceholders(model.getValue(), ctx).filter((m) => m.problem) : [];
  monaco.editor.setModelMarkers(model, MARKER_OWNER, marks.map((m) => ({
    ...rangeOf(model, m.start, m.end),
    severity: SEVERITY[m.problem!.severity],
    message: m.problem!.message,
    source: '{{ }}',
  })));
}

/**
 * Gives `model` the placeholder context of the block it edits. Returns an updater for a changed
 * context (block type, locale, block list) and a dispose function.
 */
export function attachPlaceholders(monaco: Monaco, model: Model, ctx: PlaceholderContext) {
  install(monaco);
  const key = model.uri.toString();
  contexts.set(key, ctx);
  validate(monaco, model);
  const sub = model.onDidChangeContent(() => validate(monaco, model));
  return {
    update(next: PlaceholderContext) {
      contexts.set(key, next);
      validate(monaco, model);
      hintsChanged?.fire();
    },
    dispose() {
      sub.dispose();
      contexts.delete(key);
      if (!model.isDisposed?.()) monaco.editor.setModelMarkers(model, MARKER_OWNER, []);
    },
  };
}
