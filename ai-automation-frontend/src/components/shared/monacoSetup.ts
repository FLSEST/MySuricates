import { loader } from '@monaco-editor/react';

/**
 * Serves Monaco from our own bundle instead of letting `@monaco-editor/react` fetch its runtime from
 * cdn.jsdelivr.net. Whitelisting a whole public CDN in the CSP would let an HTML injection load any
 * npm package as script, so the CSP allows 'self' only (SecurityConfig).
 *
 * Lean on purpose: the editor core plus the two languages the app edits (JSON with its language
 * worker, Markdown). The full `monaco-editor` entry would add the TypeScript/CSS/HTML workers
 * (~9 MB). Everything loads lazily with the first editor (own `monaco` chunk, not precached by the
 * PWA). `loader.config` must run before the first `loader.init()` — i.e. before an `<Editor>`
 * mounts — otherwise the loader falls back to the CDN.
 */
let ready: Promise<void> | null = null;

export function ensureLocalMonaco(): Promise<void> {
  ready ??= Promise.all([
    import('monaco-editor/esm/vs/editor/edcore.main.js'),
    import('monaco-editor/esm/vs/language/json/monaco.contribution.js'),
    import('monaco-editor/esm/vs/basic-languages/markdown/markdown.contribution.js'),
    import('monaco-editor/esm/vs/editor/editor.worker.js?worker'),
    import('monaco-editor/esm/vs/language/json/json.worker.js?worker'),
  ]).then(([monaco, , , { default: EditorWorker }, { default: JsonWorker }]) => {
    // Once defined, Monaco asks this for every worker — the JSON language service included
    (self as unknown as { MonacoEnvironment: { getWorker: (id: string, label: string) => Worker } }).MonacoEnvironment = {
      getWorker: (_id, label) => (label === 'json' ? new JsonWorker() : new EditorWorker()),
    };
    loader.config({ monaco });
  });
  return ready;
}
