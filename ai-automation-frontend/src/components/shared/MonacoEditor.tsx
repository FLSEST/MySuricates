import { useEffect, useId, useRef, useState } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import { AlignLeft } from 'lucide-react';
import { useThemeAppearance } from '../../stores/themeStore';
import { useT } from '../../i18n';
import { ensureLocalMonaco, registerJsonSchema } from './monacoSetup';

interface MonacoEditorProps {
  value: string;
  onChange?: (value: string) => void;
  language?: string;
  height?: string;
  readOnly?: boolean;
  /** Text, der beim Öffnen/Ändern angesprungen, markiert und kurz hervorgehoben wird. */
  revealText?: string;
  /** Erhöhen, um dasselbe revealText erneut anzuspringen (z. B. wiederholter Klick). */
  revealNonce?: number;
  /**
   * JSON schema for the content: Monaco then suggests keys and values (Ctrl+Space), explains them on
   * hover, flags deviations as warnings and shows a small toolbar (hint, warning count, format).
   */
  jsonSchema?: object;
  /** Fill the parent's height instead of a fixed `height` (parent must give the editor a height). */
  fill?: boolean;
}

// Monaco-Editor-Instanz (locker typisiert, um keine monaco-Typen zu importieren)
type MonacoEditorInstance = {
  getModel: () => {
    findMatches: (
      searchString: string,
      isRegex: boolean,
      matchCase: boolean,
      wholeWord: boolean,
      wordSeparators: string | null,
      captureMatches: boolean,
    ) => Array<{ range: unknown }>;
  } | null;
  revealRangeInCenter: (range: unknown) => void;
  setSelection: (range: unknown) => void;
  deltaDecorations: (
    old: string[],
    next: Array<{ range: unknown; options: { isWholeLine?: boolean; className?: string } }>,
  ) => string[];
  getAction: (id: string) => { run: () => Promise<void> } | null;
  focus: () => void;
};

type MonacoApi = {
  editor: {
    getModelMarkers: (filter: { resource: unknown }) => Array<{ severity: number }>;
    onDidChangeMarkers: (listener: (uris: unknown[]) => void) => { dispose: () => void };
  };
};

export function MonacoEditor({
  value,
  onChange,
  language = 'json',
  height = '300px',
  readOnly = false,
  revealText,
  revealNonce = 0,
  jsonSchema,
  fill = false,
}: MonacoEditorProps) {
  const t = useT();
  const appearance = useThemeAppearance();
  const editorRef = useRef<MonacoEditorInstance | null>(null);
  // Own model path per editor instance, so the schema binds to exactly this editor (fileMatch)
  const modelPath = `editor-${useId().replace(/[^a-zA-Z0-9]/g, '')}.json`;
  const [problemCount, setProblemCount] = useState(0);
  const markerListener = useRef<{ dispose: () => void } | null>(null);
  // Monaco comes from our bundle (no CDN, see monacoSetup) — mount the editor only once it is configured
  const [monacoReady, setMonacoReady] = useState(false);

  useEffect(() => {
    let active = true;
    ensureLocalMonaco().then(() => { if (active) setMonacoReady(true); });
    return () => { active = false; };
  }, []);

  const doReveal = () => {
    const editor = editorRef.current;
    if (!editor || !revealText) return;
    const model = editor.getModel();
    if (!model) return;
    const matches = model.findMatches(revealText, false, false, false, null, false);
    if (matches.length === 0) return;
    const range = matches[0]!.range;
    editor.revealRangeInCenter(range);
    editor.setSelection(range);
    const decorations = editor.deltaDecorations([], [
      { range, options: { isWholeLine: true, className: 'bpmn-reveal-flash' } },
    ]);
    window.setTimeout(() => editor.deltaDecorations(decorations, []), 1600);
  };

  useEffect(() => {
    if (!jsonSchema || !monacoReady) return;
    return registerJsonSchema(modelPath, jsonSchema);
  }, [jsonSchema, monacoReady, modelPath]);

  useEffect(() => () => markerListener.current?.dispose(), []);

  const handleMount: OnMount = (editor, monaco) => {
    if (jsonSchema) {
      const api = monaco as unknown as MonacoApi;
      const model = editor.getModel();
      const count = () => setProblemCount(model ? api.editor.getModelMarkers({ resource: model.uri }).length : 0);
      markerListener.current?.dispose();
      markerListener.current = api.editor.onDidChangeMarkers(count);
      count();
    }
    editor.updateOptions({
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      lineNumbers: 'on',
      fontFamily: "ui-monospace, 'SFMono-Regular', Menlo, Consolas, monospace",
      fontSize: 13,
      tabSize: 2,
      padding: { top: 12 },
      renderLineHighlight: 'none',
      automaticLayout: true,
      // Suggestions also inside strings — that is where JSON values like "stop"/"continue" live
      quickSuggestions: { other: true, comments: false, strings: true },
      suggest: { showInlineDetails: true, preview: true },
      hover: { enabled: true, delay: 250 },
      formatOnPaste: !!jsonSchema,
      fixedOverflowWidgets: true,
    });
    editorRef.current = editor as unknown as MonacoEditorInstance;
    doReveal();
  };

  // Beim erneuten Anspringen (revealNonce) oder geändertem Ziel neu hervorheben
  useEffect(() => {
    doReveal();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [revealText, revealNonce]);

  const editorHeight = fill ? '100%' : height;
  const format = () => {
    editorRef.current?.getAction('editor.action.formatDocument')?.run();
    editorRef.current?.focus();
  };

  return (
    <div className={`rounded-lg border border-border overflow-hidden ${fill ? 'flex flex-col flex-1 min-h-0' : ''}`}>
      {/* fill: the editor sits absolutely in a growing box — `height: 100%` has no definite parent height here */}
      <div className={fill ? 'relative flex-1 min-h-0' : undefined}>
        <div className={fill ? 'absolute inset-0' : undefined}>
          {monacoReady ? (
            <Editor
              height={editorHeight}
              path={jsonSchema ? modelPath : undefined}
              language={language}
              theme={appearance === 'dark' ? 'vs-dark' : 'light'}
              value={value}
              onChange={(val) => onChange?.(val ?? '')}
              onMount={handleMount}
              options={{ readOnly }}
            />
          ) : (
            <div style={{ height: editorHeight }} aria-busy="true" />
          )}
        </div>
      </div>
      {jsonSchema && !readOnly && (
        <div className="flex items-center justify-between gap-3 px-3 py-1.5 border-t border-border bg-surface-raised text-xs text-text-tertiary">
          <span className="min-w-0 truncate" title={t.workflows.configEditor.hint}>{t.workflows.configEditor.hint}</span>
          <div className="flex items-center gap-3 shrink-0 whitespace-nowrap">
            {problemCount > 0 && (
              <span className="text-warning" data-testid="config-problem-count">
                {t.workflows.configEditor.problems.replace('{count}', String(problemCount))}
              </span>
            )}
            <button type="button" onClick={format} className="inline-flex items-center gap-1 hover:text-text transition-colors">
              <AlignLeft className="w-3.5 h-3.5" />
              {t.workflows.configEditor.format}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
