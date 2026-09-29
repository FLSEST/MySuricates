import ts from 'typescript';
import { describe, expect, it } from 'vitest';

// Ratchet against UI text written straight into components instead of de.ts/en.ts (TODO 99ceb2b5).
// Such text shows in one language only, whatever the user picked — no type check notices.
//
// Scanned: JSX text, the visible attributes below, toast.*() / confirm() / alert() / prompt() and the
// fallback of apiErrorMessage(). Text without a word of two letters (symbols, entities, {placeholders})
// is ignored, and so is the language-neutral text in LANGUAGE_NEUTRAL.
//
// CEILINGS holds the files that still carry such text, with their current count. It only shrinks:
// a file must not gain text, a file that loses some gets its ceiling lowered, a file at zero leaves
// the list, and a new file never joins it — put the text into de.ts and en.ts instead.

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
  // Units, keys, code
  'ms',
  'ESC',
  '(Alt+ )',
  'async',
  'provider=',
  'model=',
  'requested=',
  ': sha256=<hex>',
  ': claude --resume',
  // Addresses and example values in input fields
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

/** Relative to src/, value = number of findings the file may still carry. */
const CEILINGS: Record<string, number> = {
  'components/assistant/AssistantMessage.tsx': 2,
  'components/chains/ChainDetail.tsx': 1,
  'components/chains/ChainFormModal.tsx': 1,
  'components/dashboard/StatWidgets.tsx': 2,
  'components/dashboard/TodosDonutChart.tsx': 1,
  'components/dashboard/TodosWidget.tsx': 1,
  'components/dashboard/UpcomingSchedulesCard.tsx': 1,
  'components/dialog/SessionCard.tsx': 1,
  'components/integrations/SetupGuidePanel.tsx': 1,
  'components/schedule/CalendarView.tsx': 3,
  'components/schedule/ScheduleFormModal.tsx': 1,
  'components/settings/NotificationsCard.tsx': 1,
  'components/settings/RemoteAccessCard.tsx': 1,
  'components/settings/StagingCard.tsx': 2,
  'components/settings/TestingCard.tsx': 2,
  'components/shared/CopyButton.tsx': 2,
  'components/shared/HumanInputDialog.tsx': 1,
  'components/shared/InsightsPanel.tsx': 1,
  'components/todo/StatusDots.tsx': 4,
  'components/todo/TodoKanbanView.tsx': 5,
  'components/todo/TodoListView.tsx': 1,
  'components/todo/TodoMetadata.tsx': 3,
  'components/todo/TodoTriggerSection.tsx': 1,
  'components/ui/CronPicker.tsx': 1,
  'components/ui/Modal.tsx': 1,
  'components/ui/ToastContainer.tsx': 1,
  'components/webhooks/WebhookCreateModal.tsx': 1,
  'components/wishes/ExecutionLogsPanel.tsx': 1,
  'components/wishes/WishDetail.tsx': 1,
  'components/wishes/WishIterationHistory.tsx': 1,
  'components/workflow/AgenticStepCard.tsx': 1,
  'components/workflow/ApprovalDialog.tsx': 1,
  'components/workflow/ForEachStepCard.tsx': 1,
  'components/workflow/SortableBlock.tsx': 1,
  'components/workflow/TagEditor.tsx': 1,
  'components/workflow/WorkflowMemoryItem.tsx': 1,
  'components/workflow/WorkflowRow.tsx': 2,
  'components/workflow/WorkflowVersionsTab.tsx': 1,
  'pages/ActivityLog.tsx': 1,
  'pages/AuditCenter.tsx': 1,
  'pages/DeadLetterQueue.tsx': 1,
  'pages/SimpleWorkflows.tsx': 1,
};

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

function literalText(node: ts.Node | undefined): string | undefined {
  if (!node) return undefined;
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return node.text;
  if (ts.isTemplateExpression(node)) return [node.head.text, ...node.templateSpans.map((span) => span.literal.text)].join(' ');
  if (ts.isJsxExpression(node)) return literalText(node.expression);
  return undefined;
}

function scan(file: string, source: string): Finding[] {
  const kind = file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const sf = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, kind);
  const findings: Finding[] = [];
  const add = (node: ts.Node, what: string, raw: string | undefined) => {
    const text = raw === undefined ? '' : normalize(raw);
    if (!isLanguageText(text)) return;
    findings.push({ file, line: sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1, kind: what, text });
  };
  const visit = (node: ts.Node) => {
    if (ts.isJsxText(node)) {
      add(node, 'text', node.text);
    } else if (ts.isJsxExpression(node) && ts.isJsxElement(node.parent)) {
      add(node, 'text', literalText(node.expression));
    } else if (ts.isJsxAttribute(node) && VISIBLE_ATTRIBUTES.has(node.name.getText(sf))) {
      add(node, node.name.getText(sf), literalText(node.initializer));
    } else if (ts.isCallExpression(node)) {
      const callee = node.expression.getText(sf);
      if (/^toast\.\w+$/.test(callee) || /^(window\.)?(confirm|alert|prompt)$/.test(callee)) {
        add(node, callee, literalText(node.arguments[0]));
      } else if (callee === 'apiErrorMessage') {
        add(node, callee, literalText(node.arguments[1]));
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
  it('grows in no file, and each file that carries some is listed with its exact count', () => {
    const byFile = findingsByFile();
    const problems: string[] = [];
    for (const [file, findings] of byFile) {
      const ceiling = CEILINGS[file];
      const listing = findings.map(describeFinding).join('\n');
      if (ceiling === undefined) {
        problems.push(`${file}: new hardcoded text — move it into de.ts and en.ts:\n${listing}`);
      } else if (findings.length > ceiling) {
        problems.push(`${file}: ${findings.length} hardcoded texts (ceiling ${ceiling}) — move the new one into de.ts and en.ts:\n${listing}`);
      } else if (findings.length < ceiling) {
        problems.push(`${file}: down to ${findings.length} (ceiling ${ceiling}) — lower its ceiling in CEILINGS.`);
      }
    }
    for (const file of Object.keys(CEILINGS)) {
      if (!byFile.has(file)) problems.push(`${file}: no hardcoded text left (or file gone) — remove it from CEILINGS.`);
    }
    expect(problems.join('\n\n')).toBe('');
  });

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
});
