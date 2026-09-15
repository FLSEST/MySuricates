import { describe, it, expect, vi } from 'vitest';

const config = vi.fn();
vi.mock('@monaco-editor/react', () => ({ loader: { config } }));
vi.mock('monaco-editor/esm/vs/editor/edcore.main.js', () => ({ editor: {}, marker: 'local-monaco' }));
const contributions: string[] = [];
vi.mock('monaco-editor/esm/vs/language/json/monaco.contribution.js', () => {
  contributions.push('json');
  return {};
});
vi.mock('monaco-editor/esm/vs/basic-languages/markdown/markdown.contribution.js', () => {
  contributions.push('markdown');
  return {};
});
class EditorWorker { kind = 'editor'; }
class JsonWorker { kind = 'json'; }
vi.mock('monaco-editor/esm/vs/editor/editor.worker.js?worker', () => ({ default: EditorWorker }));
vi.mock('monaco-editor/esm/vs/language/json/json.worker.js?worker', () => ({ default: JsonWorker }));

describe('ensureLocalMonaco', () => {
  it('points the loader at the bundled Monaco once, with the JSON and Markdown languages and bundled workers', async () => {
    const { ensureLocalMonaco } = await import('../monacoSetup');

    await Promise.all([ensureLocalMonaco(), ensureLocalMonaco()]);

    expect(config).toHaveBeenCalledTimes(1);
    expect(config.mock.calls[0]![0].monaco).toMatchObject({ marker: 'local-monaco' });
    expect(contributions.sort()).toEqual(['json', 'markdown']);
    const env = (self as unknown as { MonacoEnvironment: { getWorker: (id: string, label: string) => { kind: string } } })
      .MonacoEnvironment;
    expect(env.getWorker('1', 'json').kind).toBe('json');
    expect(env.getWorker('2', 'editorWorkerService').kind).toBe('editor');
  });
});
