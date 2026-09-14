import { CopyButton } from '../shared/CopyButton';

/** Monospace configuration snippet with a label and a copy button. */
export function ConfigSnippet({ label, text }: { label: string; text: string }) {
  return (
    <div>
      <p className="text-[11px] uppercase tracking-wide text-text-tertiary mb-1">{label}</p>
      <div className="relative rounded-lg border border-border bg-surface px-3 py-2 font-mono text-xs text-text">
        <pre className="whitespace-pre overflow-x-auto pr-7">{text}</pre>
        <div className="absolute top-1.5 right-1.5">
          <CopyButton text={text} />
        </div>
      </div>
    </div>
  );
}
