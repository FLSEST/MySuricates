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

type JsonSchemaEntry = { uri: string; fileMatch: string[]; schema: object };
type JsonDefaults = { setDiagnosticsOptions: (options: Record<string, unknown>) => void };
let jsonDefaults: JsonDefaults | null = null;
const jsonSchemas = new Map<string, JsonSchemaEntry>();

function applyJsonSchemas() {
  jsonDefaults?.setDiagnosticsOptions({
    validate: true,
    allowComments: false,
    enableSchemaRequest: false,
    // Schema findings are hints — the backend has the final say, saving is never blocked by them
    schemaValidation: 'warning',
    schemas: [...jsonSchemas.values()],
  });
}

/**
 * Attaches a JSON schema to the editor model at `modelPath` (completion, hover, warnings). Every editor
 * registers under its own path, so several JSON editors on one page never overwrite each other's schema.
 * Returns the unregister function.
 */
export function registerJsonSchema(modelPath: string, schema: object): () => void {
  const uri = `inmemory://schema/${encodeURIComponent(modelPath)}`;
  jsonSchemas.set(modelPath, { uri, fileMatch: [modelPath], schema });
  applyJsonSchemas();
  return () => {
    if (jsonSchemas.get(modelPath)?.schema === schema) {
      jsonSchemas.delete(modelPath);
      applyJsonSchemas();
    }
  };
}

export function ensureLocalMonaco(): Promise<void> {
  // Since Monaco 0.56 the package only exports these entry points: `editor` is the bare core,
  // `features/register.all` adds the editor features (find, hover, folding, …), languages register one by one
  ready ??= Promise.all([
    import('monaco-editor/editor'),
    import('monaco-editor/features/register.all'),
    import('monaco-editor/languages/features/json/register'),
    import('monaco-editor/languages/definitions/markdown/register'),
    import('monaco-editor/editor/editor.worker.js?worker'),
    import('monaco-editor/languages/features/json/json.worker.js?worker'),
  ]).then(([monaco, , json, , { default: EditorWorker }, { default: JsonWorker }]) => {
    // Monaco no longer hangs the JSON defaults off `monaco.languages.json` — take them from the registration
    jsonDefaults = (json as { jsonDefaults?: JsonDefaults }).jsonDefaults ?? null;
    applyJsonSchemas();
    // Once defined, Monaco asks this for every worker — the JSON language service included
    (self as unknown as { MonacoEnvironment: { getWorker: (id: string, label: string) => Worker } }).MonacoEnvironment = {
      getWorker: (_id, label) => (label === 'json' ? new JsonWorker() : new EditorWorker()),
    };
    loader.config({ monaco });
  });
  return ready;
}
