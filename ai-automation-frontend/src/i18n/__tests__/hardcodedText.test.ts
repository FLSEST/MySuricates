import ts from 'typescript';
import { describe, expect, it } from 'vitest';

// Guard against UI text written straight into components instead of de.ts/en.ts (TODO 99ceb2b5).
// Such text shows in one language only, whatever the user picked — no type check notices.
//
// Scanned: JSX text, the visible attributes below, toast.*() / confirm() / alert() / prompt() and the
// fallback of apiErrorMessage(). Text without a word of two letters (symbols, entities, {placeholders})
// is ignored, and so is the language-neutral text in LANGUAGE_NEUTRAL. Everything else fails: put it
// into de.ts and en.ts, or — only if it really reads the same in both languages — into LANGUAGE_NEUTRAL.

const SOURCES = import.meta.glob<string>(
  ['../../**/*.{ts,tsx}', '!../../**/__tests__/**', '!../../**/*.test.*', '!../../**/*.d.ts', '!../../i18n/**', '!../../test/**'],
  { query: '?raw', import: 'default', eager: true },
);

const VISIBLE_ATTRIBUTES = new Set(['title', 'placeholder', 'aria-label', 'alt', 'label']);

/** Text that reads the same in every language. Exact match after whitespace is collapsed. */
const LANGUAGE_NEUTRAL = new Set([
  // Names
  'MySuricates',
  'Docker',
  // A language named in itself, and language codes
  'Deutsch',
  'English',
  'DE',
  'EN',
  // Terms the German UI keeps as they are
  'Run',
  'Run:',
  'Run →',
  'Runs',
  'Snapshot',
  'Stable',
  'Test',
  'global',
  'Hostname:',
  'Cron:',
  'Online',
  'Webhook',
  'Desktop',
  'Client ID',
  'Client Secret',
  'IP:',
  // Abbreviations, units, keys, code
  'BE',
  'FE',
  'vs',
  'max',
  'tok',
  'ms',
  'ESC',
  '(Alt+ )',
  'async',
  '· exit',
  'provider=',
  'model=',
  'requested=',
  ': sha256=<hex>',
  ': claude --resume',
  // Addresses and example values in input fields
  '/home/user/projects/my-project',
  'localhost:8090',
  'localhost:9091',
  'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx',
  'sk-...',
  'https://hooks.slack.com/services/...',
  'https://mysuricates.example.com',
  '{{ block.<name>.output }}',
  'backend_tests',
  'backend,testing',
  'my-profile',
  'tag1, tag2, tag3',
  'bug, performance, ux',
]);

interface Finding {
  file: string;
  line: number;
  kind: string;
  text: string;
}

const ENTITIES: Record<string, string> = { '&lt;': '<', '&gt;': '>', '&amp;': '&', '&quot;': '"', '&nbsp;': ' ' };

function normalize(text: string): string {
  return text
    .replace(/&\w+;/g, (entity) => ENTITIES[entity] ?? ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function isLanguageText(text: string): boolean {
  return /\p{L}{2,}/u.test(text.replace(/\{[^}]*\}/g, '')) && !LANGUAGE_NEUTRAL.has(text);
}

/**
 * Every literal a node can evaluate to: a string or template, both branches of `a ? b : c`, the right side of
 * `a && b` and both sides of `a || b` / `a ?? b`, through parentheses. A ternary child once hid
 * "Hide items" / `Show ${n} items` from this gate.
 */
function literalTexts(node: ts.Node | undefined): string[] {
  if (!node) return [];
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return [node.text];
  if (ts.isTemplateExpression(node)) return [[node.head.text, ...node.templateSpans.map((span) => span.literal.text)].join(' ')];
  if (ts.isJsxExpression(node) || ts.isParenthesizedExpression(node)) return literalTexts(node.expression);
  if (ts.isConditionalExpression(node)) return [...literalTexts(node.whenTrue), ...literalTexts(node.whenFalse)];
  if (ts.isBinaryExpression(node)) {
    const op = node.operatorToken.kind;
    if (op === ts.SyntaxKind.AmpersandAmpersandToken) return literalTexts(node.right);
    if (op === ts.SyntaxKind.BarBarToken || op === ts.SyntaxKind.QuestionQuestionToken) {
      return [...literalTexts(node.left), ...literalTexts(node.right)];
    }
  }
  return [];
}

function scan(file: string, source: string): Finding[] {
  const kind = file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const sf = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, kind);
  const findings: Finding[] = [];
  const add = (node: ts.Node, what: string, raws: string[]) => {
    for (const raw of raws) {
      const text = normalize(raw);
      if (!isLanguageText(text)) continue;
      findings.push({ file, line: sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1, kind: what, text });
    }
  };
  const visit = (node: ts.Node) => {
    if (ts.isJsxText(node)) {
      add(node, 'text', [node.text]);
    } else if (ts.isJsxExpression(node) && ts.isJsxElement(node.parent)) {
      add(node, 'text', literalTexts(node.expression));
    } else if (ts.isJsxAttribute(node) && VISIBLE_ATTRIBUTES.has(node.name.getText(sf))) {
      add(node, node.name.getText(sf), literalTexts(node.initializer));
    } else if (ts.isCallExpression(node)) {
      const callee = node.expression.getText(sf);
      if (/^toast\.\w+$/.test(callee) || /^(window\.)?(confirm|alert|prompt)$/.test(callee)) {
        add(node, callee, literalTexts(node.arguments[0]));
      } else if (callee === 'apiErrorMessage') {
        add(node, callee, literalTexts(node.arguments[1]));
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return findings;
}

function findingsByFile(): Map<string, Finding[]> {
  const byFile = new Map<string, Finding[]>();
  for (const [path, source] of Object.entries(SOURCES)) {
    const file = path.replace(/^\.\.\/\.\.\//, '');
    const findings = scan(file, source);
    if (findings.length > 0) byFile.set(file, findings);
  }
  return byFile;
}

const describeFinding = (f: Finding) => `  ${f.file}:${f.line} ${f.kind}: "${f.text}"`;

describe('hardcoded UI text', () => {
  // Parses every component source; under load (parallel test files, busy host) this exceeds the 5 s default.
  it('is found in no component', () => {
    const listing = [...findingsByFile().values()].flat().map(describeFinding);

    expect(listing, 'move this text into de.ts and en.ts').toEqual([]);
  }, 30_000);

  it('finds text in every place it scans, and skips what reads the same in every language', () => {
    const source = [
      'export function X() {',
      '  toast.error("Speichern fehlgeschlagen");',
      '  window.confirm(`Delete ${name}?`);',
      '  apiErrorMessage(err, "Could not load");',
      '  return (<div title="Close" data-x="Ignored attribute">',
      '    Plain text',
      "    {'Literal child'}",
      '    <b aria-label={`Remove ${tag}`}>&larr;</b>',
      '    MySuricates {count} &middot; {"{id}"}',
      '  </div>);',
      '}',
    ].join('\n');

    const texts = scan('Example.tsx', source).map((f) => `${f.kind}: ${f.text}`);

    expect(texts).toEqual([
      'toast.error: Speichern fehlgeschlagen',
      'window.confirm: Delete ?',
      'apiErrorMessage: Could not load',
      'title: Close',
      'text: Plain text',
      'text: Literal child',
      'aria-label: Remove',
    ]);
  });

  // a5c9fbbf follow-up: "Hide items" / `Show ${n} items` in a ternary child slipped through
  it('finds text in the branches of a conditional child and behind && / || / ??', () => {
    const source = [
      'export function X() {',
      '  return (<div title={open ? "Collapse" : "Expand"}>',
      "    {expanded ? 'Hide items' : `Show ${n} items`}",
      "    {busy && 'Loading data'}",
      "    {(label ?? 'No label')}",
      '    {count > 0 ? count : null}',
      '  </div>);',
      '}',
    ].join('\n');

    const texts = scan('Example.tsx', source).map((f) => `${f.kind}: ${f.text}`);

    expect(texts).toEqual([
      'title: Collapse',
      'title: Expand',
      'text: Hide items',
      'text: Show items',
      'text: Loading data',
      'text: No label',
    ]);
  });
});
