import { describe, expect, it } from 'vitest';

// Gate against fixed Tailwind palette classes (bg-red-500, dark:text-green-300 …) in the frontend (TODO 19ebb8a5).
// They ignore the chosen theme: a status must read `text-danger`/`bg-success-subtle`, a role `text-ai`, the
// normal case stays neutral (`text-text-secondary`, `border-border-strong`), categorical colours use the chart
// tokens (`bg-chart-1..8` / `var(--color-chart-N)`). See CLAUDE.md → Key Conventions → Styling.
//
// EXCEPTIONS lists the only files that may still carry palette classes, each with its reason. A file on the
// list that no longer carries any must leave it, so the list only ever shrinks.

const SOURCES = import.meta.glob<string>(
  ['../../**/*.{ts,tsx}', '!../../**/__tests__/**', '!../../**/*.test.*', '!../../**/*.d.ts', '!../../test/**'],
  { query: '?raw', import: 'default', eager: true },
);

/** Relative to src/, value = why the file keeps fixed palette colours. */
const EXCEPTIONS: Record<string, string> = {
  'components/shared/LogViewer.tsx':
    'always-dark terminal look: log levels and ANSI colours on a fixed dark background, independent of the theme',
  'theme/derive.ts':
    'implements the remap of Tailwind palette classes onto palette themes and names them in its comments',
};

const FAMILIES = [
  'red', 'orange', 'amber', 'yellow', 'lime', 'green', 'emerald', 'teal', 'cyan', 'sky', 'blue', 'indigo',
  'violet', 'purple', 'fuchsia', 'pink', 'rose', 'slate', 'gray', 'zinc', 'neutral', 'stone',
];

const UTILITIES = [
  'bg', 'text', 'border(?:-[trblxyse])?', 'ring(?:-offset)?', 'from', 'to', 'via', 'fill', 'stroke', 'divide',
  'outline', 'decoration', 'shadow', 'placeholder', 'accent', 'caret',
];

const PALETTE_CLASS = new RegExp(
  `(?<![\\w-])(?:${UTILITIES.join('|')})-(?:${FAMILIES.join('|')})-(?:50|[1-9]00|950)(?:/\\d+)?(?![\\w-])`,
  'g',
);

interface Finding {
  file: string;
  line: number;
  match: string;
}

function scan(file: string, source: string): Finding[] {
  return source.split('\n').flatMap((text, i) =>
    [...text.matchAll(PALETTE_CLASS)].map((m) => ({ file, line: i + 1, match: m[0] })));
}

function findingsByFile(): Map<string, Finding[]> {
  const byFile = new Map<string, Finding[]>();
  for (const [path, source] of Object.entries(SOURCES)) {
    // Glob keys are relative to this test ('../derive.ts', '../../pages/X.tsx'); report them relative to src/
    const file = new URL(path, 'file:///src/theme/__tests__/').pathname.replace(/^\/src\//, '');
    const findings = scan(file, source);
    if (findings.length > 0) byFile.set(file, findings);
  }
  return byFile;
}

describe('fixed Tailwind palette classes', () => {
  it('appear only in the files listed in EXCEPTIONS', () => {
    const byFile = findingsByFile();
    const problems: string[] = [];
    for (const [file, findings] of byFile) {
      if (EXCEPTIONS[file]) continue;
      const listing = findings.map((f) => `  ${f.file}:${f.line} ${f.match}`).join('\n');
      problems.push(
        `${file}: fixed palette classes — use theme tokens instead (CLAUDE.md → Key Conventions → Styling):\n${listing}`,
      );
    }
    for (const file of Object.keys(EXCEPTIONS)) {
      if (!byFile.has(file)) problems.push(`${file}: no palette classes left (or file gone) — remove it from EXCEPTIONS.`);
    }
    expect(problems.join('\n\n')).toBe('');
  });

  it('finds palette classes with variants, sides and opacity, and leaves theme tokens alone', () => {
    const source = [
      "const a = 'bg-red-500 hover:text-green-300 dark:bg-slate-900/40';",
      "const b = 'border-t-amber-200 ring-offset-sky-50 from-violet-950 text-balance';",
      "const c = 'bg-danger text-success bg-chart-1 border-border-strong bg-[#211a14] text-2xs';",
      "const d = 'shadow-lg text-red-5000 my-bg-blue-500';",
    ].join('\n');

    expect(scan('Example.tsx', source).map((f) => `${f.line}:${f.match}`)).toEqual([
      '1:bg-red-500',
      '1:text-green-300',
      '1:bg-slate-900/40',
      '2:border-t-amber-200',
      '2:ring-offset-sky-50',
      '2:from-violet-950',
    ]);
  });
});
