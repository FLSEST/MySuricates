import { describe, it, expect, vi } from 'vitest';

const config = vi.fn();
vi.mock('@monaco-editor/react', () => ({ loader: { config } }));
vi.mock('monaco-editor/editor', () => ({ editor: {}, marker: 'local-monaco' }));
const features: string[] = [];
vi.mock('monaco-editor/features/register.all', () => {
  features.push('all');
  return {};
});
const contributions: string[] = [];
const setDiagnosticsOptions = vi.fn();
vi.mock('monaco-editor/languages/features/json/register', () => {
  contributions.push('json');
  return { jsonDefaults: { setDiagnosticsOptions } };
});
vi.mock('monaco-editor/languages/definitions/markdown/register', () => {
  contributions.push('markdown');
  return {};
});
class EditorWorker { kind = 'editor'; }
class JsonWorker { kind = 'json'; }
vi.mock('monaco-editor/editor/editor.worker.js?worker', () => ({ default: EditorWorker }));
vi.mock('monaco-editor/languages/features/json/json.worker.js?worker', () => ({ default: JsonWorker }));

describe('ensureLocalMonaco', () => {
  it('points the loader at the bundled Monaco once, with the editor features, JSON and Markdown and bundled workers', async () => {
    const { ensureLocalMonaco } = await import('../monacoSetup');

    await Promise.all([ensureLocalMonaco(), ensureLocalMonaco()]);

    expect(config).toHaveBeenCalledTimes(1);
    expect(config.mock.calls[0]![0].monaco).toMatchObject({ marker: 'local-monaco' });
    expect(features).toEqual(['all']);
    expect(contributions.sort()).toEqual(['json', 'markdown']);
    const env = (self as unknown as { MonacoEnvironment: { getWorker: (id: string, label: string) => { kind: string } } })
      .MonacoEnvironment;
    expect(env.getWorker('1', 'json').kind).toBe('json');
    expect(env.getWorker('2', 'editorWorkerService').kind).toBe('editor');
  });

  it('keeps one schema per editor model and only warns about schema deviations', async () => {
    const { ensureLocalMonaco, registerJsonSchema } = await import('../monacoSetup');
    await ensureLocalMonaco();
    const latest = () => setDiagnosticsOptions.mock.calls.at(-1)![0];

    const unregisterA = registerJsonSchema('editor-a.json', { title: 'A' });
    registerJsonSchema('editor-b.json', { title: 'B' });
    expect(latest().schemaValidation).toBe('warning');
    expect(latest().enableSchemaRequest).toBe(false);
    expect(latest().schemas.map((s: { fileMatch: string[] }) => s.fileMatch[0])).toEqual(['editor-a.json', 'editor-b.json']);

    // A newer schema for the same editor replaces the old one; the stale unregister must not remove it
    registerJsonSchema('editor-a.json', { title: 'A2' });
    unregisterA();
    expect(latest().schemas.map((s: { schema: { title: string } }) => s.schema.title)).toEqual(['A2', 'B']);
  });
});
