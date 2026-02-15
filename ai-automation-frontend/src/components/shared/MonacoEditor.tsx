import Editor, { type OnMount } from '@monaco-editor/react';
import { useThemeStore } from '../../stores/themeStore';

interface MonacoEditorProps {
  value: string;
  onChange?: (value: string) => void;
  language?: string;
  height?: string;
  readOnly?: boolean;
}

export function MonacoEditor({
  value,
  onChange,
  language = 'json',
  height = '300px',
  readOnly = false,
}: MonacoEditorProps) {
  const mode = useThemeStore((s) => s.mode);

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
  };

  return (
    <div className="rounded-lg border border-border overflow-hidden">
      <Editor
        height={height}
        language={language}
        theme={mode === 'dark' ? 'vs-dark' : 'light'}
        value={value}
        onChange={(val) => onChange?.(val ?? '')}
        onMount={handleMount}
        options={{ readOnly }}
      />
    </div>
  );
}
