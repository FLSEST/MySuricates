import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';

// components/shared importiert keine Fachordner (workflow, goal, hints, todo, settings …), nur
// ui und die fachfreien Ordner unter src (docs/frontend.md → Shared-Komponenten). Die Regel prueft
// den Importpfad als Text, deshalb je Verzeichnistiefe unter shared eigene Muster: `depth` ist die
// Zahl der Ordner zwischen shared und der Datei (0 = shared/X.tsx, 1 = shared/__tests__/X.test.tsx).
const SHARED_ALLOWED_IN_COMPONENTS = ['ui', 'shared'];
const SHARED_ALLOWED_IN_SRC = ['api', 'hooks', 'utils', 'types', 'stores', 'i18n', 'test', 'components/ui', 'components/shared'];
const SHARED_IMPORT_MESSAGE =
  'components/shared importiert keine Fachordner, nur ui, api, hooks, utils, types, stores und i18n '
  + '(docs/frontend.md → Shared-Komponenten). Die Komponente gehoert in den Fachordner oder der Helfer nach src/utils.';

// Die Tiefen, fuer die es Muster gibt; tiefer liegt unter shared heute nichts (nur __tests__).
const SHARED_DEPTHS = [0, 1, 2, 3, 4];

function sharedImportPatterns(depth) {
  // Ein Schritt nach oben, auch mit eingestreutem `./` (`./../x`, `.././x`)
  const up = '(?:\\./)*\\.\\./';
  // Ende der Aufwaerts-Schritte: danach weder `.` noch `..`
  const stop = '(?:\\./)*(?!\\.\\.?(?:/|$))';
  const toShared = up.repeat(depth);
  const oneOf = (dirs) => `(?:${dirs.join('|')})(?:/|$)`;
  return [
    // ../X → src/components/X
    `^${toShared}${up}${stop}(?!${oneOf(SHARED_ALLOWED_IN_COMPONENTS)})`,
    // ../../X → src/X
    `^${toShared}${up}${up}${stop}(?!${oneOf(SHARED_ALLOWED_IN_SRC)})`,
    // ../../../ → ausserhalb von src
    `^${toShared}${up}${up}${up}`,
    // @/X → src/X (Pfad-Alias aus tsconfig.json)
    `^@/(?!${oneOf(SHARED_ALLOWED_IN_SRC)})`,
  ];
}

function sharedImportRules(depth) {
  const patterns = sharedImportPatterns(depth);
  return {
    'no-restricted-imports': ['error', {
      patterns: patterns.map((regex) => ({ regex, message: SHARED_IMPORT_MESSAGE })),
    }],
    // Dynamisches import() prueft no-restricted-imports nicht. esquery-Regexe duerfen kein `/` enthalten.
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
