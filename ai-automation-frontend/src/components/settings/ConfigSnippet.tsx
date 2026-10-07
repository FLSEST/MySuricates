import { CopyButton } from '../shared/CopyButton';

/**
 * Monospace configuration snippet with a label and a copy button. `copyText` lets the shown text
 * differ from the copied one — e.g. a masked secret on screen, the real value on the clipboard.
 */
export function ConfigSnippet({ label, text, copyText }: { label: string; text: string; copyText?: string }) {
  return (
    <div className="min-w-0">
      <p className="text-[11px] uppercase tracking-wide text-text-tertiary mb-1">{label}</p>
      <div className="flex items-start gap-2 rounded-lg border border-border bg-surface px-3 py-2 font-mono text-xs text-text">
        {/* pre-wrap keeps YAML indentation; break-all wraps long env var names instead of scrolling */}
        <pre className="min-w-0 flex-1 whitespace-pre-wrap break-all">{text}</pre>
        <CopyButton text={copyText ?? text} className="shrink-0 p-0.5 rounded-sm text-text-tertiary hover:text-accent transition-colors" />
      </div>
    </div>
  );
}
