import { useEffect, useRef } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import { useThemeAppearance } from '../../stores/themeStore';

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
};

export function MonacoEditor({
  value,
  onChange,
  language = 'json',
  height = '300px',
  readOnly = false,
  revealText,
  revealNonce = 0,
}: MonacoEditorProps) {
  const appearance = useThemeAppearance();
  const editorRef = useRef<MonacoEditorInstance | null>(null);

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

  const handleMount: OnMount = (editor) => {
    editor.updateOptions({
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      lineNumbers: 'on',
      fontFamily: 'JetBrains Mono, Fira Code, monospace',
      fontSize: 13,
      tabSize: 2,
      padding: { top: 12 },
      renderLineHighlight: 'none',
    });
    editorRef.current = editor as unknown as MonacoEditorInstance;
    doReveal();
  };

  // Beim erneuten Anspringen (revealNonce) oder geändertem Ziel neu hervorheben
  useEffect(() => {
    doReveal();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [revealText, revealNonce]);

  return (
    <div className="rounded-lg border border-border overflow-hidden">
      <Editor
        height={height}
        language={language}
        theme={appearance === 'dark' ? 'vs-dark' : 'light'}
        value={value}
        onChange={(val) => onChange?.(val ?? '')}
        onMount={handleMount}
        options={{ readOnly }}
      />
    </div>
  );
}
