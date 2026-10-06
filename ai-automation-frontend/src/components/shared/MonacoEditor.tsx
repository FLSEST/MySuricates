import { useEffect, useId, useMemo, useRef, useState } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import { AlignLeft } from 'lucide-react';
import { useThemeAppearance } from '../../stores/themeStore';
import { useT } from '../../i18n';
import { ensureLocalMonaco, registerJsonSchema } from './monacoSetup';
import { attachPlaceholders } from './monacoPlaceholders';
import { MonacoValueSync } from './monacoValueSync';
import type { PlaceholderContext } from '../../utils/placeholderContext';

interface MonacoEditorProps {
  /**
   * The text. Echoes of the editor's own `onChange` are not written back (they may be older than
   * what was typed meanwhile); any other value replaces the content, e.g. after loading or a reset.
   */
  value: string;
  onChange?: (value: string) => void;
  language?: string;
  height?: string;
  readOnly?: boolean;
  /** Text that is jumped to, selected and briefly highlighted on opening/changing. */
  revealText?: string;
  /** Increase to jump to the same revealText again (e.g. repeated click). */
  revealNonce?: number;
  /**
   * JSON schema for the content: Monaco then suggests keys and values (Ctrl+Space), explains them on
   * hover, flags deviations as warnings and shows a small toolbar (hint, warning count, format).
   */
  jsonSchema?: object;
  /** Fill the parent's height instead of a fixed `height` (parent must give the editor a height). */
  fill?: boolean;
  /**
   * Prose such as Markdown: wrap long lines and offer no word suggestions — with them, Enter at the
   * end of a word that already occurs in the text accepts the suggestion instead of breaking the line.
   */
  prose?: boolean;
  /** `{{ … }}` bound at this block: suggestions after `{{`, origin hint and hover, warnings. */
  placeholders?: PlaceholderContext;
  /**
   * Model path. One path per document (e.g. per profile and language) gives each its own model,
   * undo history and scroll position while the parent switches between them. Without it the editor
   * keeps one model per instance.
   */
  path?: string;
  /** Line numbers in the gutter — prose that nobody refers to by line can do without them. */
  lineNumbers?: 'on' | 'off';
}

// Monaco editor instance (loosely typed, to avoid importing monaco types)
type MonacoEditorInstance = {
  getValue: () => string;
  setValue: (value: string) => void;
  executeEdits: (source: string, edits: Array<{ range: unknown; text: string; forceMoveMarkers?: boolean }>) => boolean;
  pushUndoStop: () => void;
  getModel: () => {
    getFullModelRange: () => unknown;
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

const PROSE_OPTIONS = {
  wordWrap: 'on',
  quickSuggestions: false,
  wordBasedSuggestions: 'off',
  suggestOnTriggerCharacters: false,
} as const;

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
  prose = false,
  placeholders,
  path,
  lineNumbers = 'on',
}: MonacoEditorProps) {
  const t = useT();
  const appearance = useThemeAppearance();
  const editorRef = useRef<MonacoEditorInstance | null>(null);
  // Own model path per editor instance, so the schema binds to exactly this editor (fileMatch)
  const instancePath = `editor-${useId().replace(/[^a-zA-Z0-9]/g, '')}.json`;
  const modelPath = path ?? instancePath;
  const [problemCount, setProblemCount] = useState(0);
  const markerListener = useRef<{ dispose: () => void } | null>(null);
  const placeholderHandle = useRef<ReturnType<typeof attachPlaceholders> | null>(null);
  // Latest context for the mount callback, which may run a render later than it was created
  const placeholdersRef = useRef(placeholders);
  // Monaco comes from our bundle (no CDN, see monacoSetup) — mount the editor only once it is configured
  const [monacoReady, setMonacoReady] = useState(false);
  // The editor itself is uncontrolled; `value` reaches the model only when it is no echo (monacoValueSync)
  const [mounted, setMounted] = useState(false);
  const valueSync = useRef(new MonacoValueSync());
  const applyingValue = useRef(false);
  const editorOptions = useMemo(
    () => (prose ? { readOnly, lineNumbers, ...PROSE_OPTIONS } : { readOnly, lineNumbers }),
    [prose, readOnly, lineNumbers],
  );

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

  useEffect(() => () => {
    markerListener.current?.dispose();
    placeholderHandle.current?.dispose();
  }, []);

  useEffect(() => {
    placeholdersRef.current = placeholders;
    if (placeholders) placeholderHandle.current?.update(placeholders);
  }, [placeholders]);

  const handleMount: OnMount = (editor, monaco) => {
    if (jsonSchema) {
      const api = monaco as unknown as MonacoApi;
      const model = editor.getModel();
      // Warnings and errors only (severity ≥ 4) — hints like "previous_output is empty here" stay uncounted
      const count = () => setProblemCount(model ? api.editor.getModelMarkers({ resource: model.uri }).filter((m) => m.severity >= 4).length : 0);
      markerListener.current?.dispose();
      markerListener.current = api.editor.onDidChangeMarkers(count);
      count();
    }
    const model = editor.getModel();
    if (placeholdersRef.current && model) {
      placeholderHandle.current?.dispose();
      placeholderHandle.current = attachPlaceholders(monaco, model as unknown as Parameters<typeof attachPlaceholders>[1], placeholdersRef.current);
    }
    editor.updateOptions({
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      lineNumbers,
      fontFamily: "ui-monospace, 'SFMono-Regular', Menlo, Consolas, monospace",
      fontSize: 13,
      tabSize: 2,
      padding: { top: 12 },
      renderLineHighlight: 'none',
      automaticLayout: true,
      // Suggestions also inside strings — that is where JSON values like "stop"/"continue" live
      quickSuggestions: { other: true, comments: false, strings: true },
      suggest: { showInlineDetails: true, preview: true },
      hover: { enabled: 'on', delay: 250 },
      formatOnPaste: !!jsonSchema,
      fixedOverflowWidgets: true,
      // Prose keeps its switches: the suggestions above would bring back Enter accepting a word
      ...(prose ? PROSE_OPTIONS : {}),
    });
    editorRef.current = editor as unknown as MonacoEditorInstance;
    valueSync.current.mountedWith(editor.getValue());
    doReveal();
    setMounted(true);
  };

  const handleChange = (text: string | undefined) => {
    if (applyingValue.current) return; // our own write of an outside value, not an edit
    const next = text ?? '';
    valueSync.current.reportedChange(next);
    onChange?.(next);
  };

  useEffect(() => {
    const editor = editorRef.current;
    if (!mounted || !editor || !valueSync.current.shouldApply(value, editor.getValue())) return;
    applyingValue.current = true;
    try {
      if (readOnly) {
        editor.setValue(value);
      } else {
        // One undoable edit, as @monaco-editor/react does it for a controlled value
        editor.executeEdits('', [{ range: editor.getModel()!.getFullModelRange(), text: value, forceMoveMarkers: true }]);
        editor.pushUndoStop();
      }
    } finally {
      applyingValue.current = false;
    }
  }, [value, mounted, readOnly]);

  // Highlight again on repeated jumping (revealNonce) or a changed target
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
              path={path ?? (jsonSchema ? modelPath : undefined)}
              language={language}
              theme={appearance === 'dark' ? 'vs-dark' : 'light'}
              defaultValue={value}
              onChange={handleChange}
              onMount={handleMount}
              options={editorOptions}
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
