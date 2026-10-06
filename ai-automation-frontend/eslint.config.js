import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';

// components/shared imports no domain folders (workflow, goal, hints, todo, settings …), only
// ui and the domain-free folders under src (docs/frontend.md → Shared-Komponenten). The rule checks
// the import path as text, hence separate patterns per directory depth under shared: `depth` is the
// number of folders between shared and the file (0 = shared/X.tsx, 1 = shared/__tests__/X.test.tsx).
const SHARED_ALLOWED_IN_COMPONENTS = ['ui', 'shared'];
const SHARED_ALLOWED_IN_SRC = ['api', 'hooks', 'utils', 'types', 'stores', 'i18n', 'test', 'components/ui', 'components/shared'];
const SHARED_IMPORT_MESSAGE =
  'components/shared importiert keine Fachordner, nur ui, api, hooks, utils, types, stores und i18n '
  + '(docs/frontend.md → Shared-Komponenten). Die Komponente gehoert in den Fachordner oder der Helfer nach src/utils.';

// The depths for which patterns exist; deeper, nothing lies under shared today (only __tests__).
const SHARED_DEPTHS = [0, 1, 2, 3, 4];

function sharedImportPatterns(depth) {
  // One step up, also with an interspersed `./` (`./../x`, `.././x`)
  const up = '(?:\\./)*\\.\\./';
  // End of the upward steps: afterwards neither `.` nor `..`
  const stop = '(?:\\./)*(?!\\.\\.?(?:/|$))';
  const toShared = up.repeat(depth);
  const oneOf = (dirs) => `(?:${dirs.join('|')})(?:/|$)`;
  return [
    // ../X → src/components/X
    `^${toShared}${up}${stop}(?!${oneOf(SHARED_ALLOWED_IN_COMPONENTS)})`,
    // ../../X → src/X
    `^${toShared}${up}${up}${stop}(?!${oneOf(SHARED_ALLOWED_IN_SRC)})`,
    // ../../../ → outside src
    `^${toShared}${up}${up}${up}`,
    // @/X → src/X (path alias from tsconfig.json)
    `^@/(?!${oneOf(SHARED_ALLOWED_IN_SRC)})`,
  ];
}

function sharedImportRules(depth) {
  const patterns = sharedImportPatterns(depth);
  return {
    'no-restricted-imports': ['error', {
      patterns: patterns.map((regex) => ({ regex, message: SHARED_IMPORT_MESSAGE })),
    }],
    // Dynamic import() is not checked by no-restricted-imports. esquery regexes must not contain `/`.
    'no-restricted-syntax': ['error', ...patterns.map((regex) => ({
      selector: `ImportExpression[source.value=/${regex.replaceAll('/', '\\x2F')}/]`,
      message: SHARED_IMPORT_MESSAGE,
    }))],
  };
}

export default tseslint.config(
  { ignores: ['dist/', 'node_modules/', 'cypress/', 'playwright/'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
      'react-hooks/set-state-in-effect': 'warn',
      '@typescript-eslint/no-unused-vars': ['warn', {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
      }],
    },
  },
  ...SHARED_DEPTHS.map((depth) => ({
    files: [`src/components/shared/${'*/'.repeat(depth)}*.{ts,tsx}`],
    rules: sharedImportRules(depth),
  })),
  {
    files: ['**/*.test.{ts,tsx}', '**/__tests__/**/*.{ts,tsx}'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
);
