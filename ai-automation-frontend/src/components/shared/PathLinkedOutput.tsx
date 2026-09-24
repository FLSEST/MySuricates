import { openPath } from '../../api/client';
import { useToast } from '../../hooks/useToast';
import { useT } from '../../i18n';

/** Absolute file paths in step output (global — use with matchAll or reset lastIndex). */
export const PATH_REGEX = /(\/(?:[\w.-]+\/)+[\w.-]+)/g;

export function PathLinkedOutput({ text }: { text: string }) {
  const toast = useToast();
  const t = useT();
  const parts: Array<{ type: 'text' | 'path'; value: string }> = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  // Reset regex state (global regex requires resetting lastIndex)
  PATH_REGEX.lastIndex = 0; // eslint-disable-line react-hooks/immutability

  while ((match = PATH_REGEX.exec(text)) !== null) {
    const matchStart = match.index;
    const matched = match[1] ?? match[0];
    if (matchStart > lastIndex) {
      parts.push({ type: 'text', value: text.slice(lastIndex, matchStart) });
    }
    parts.push({ type: 'path', value: matched! });
    lastIndex = matchStart + matched!.length;
  }
  if (lastIndex < text.length) {
    parts.push({ type: 'text', value: text.slice(lastIndex) });
  }

  if (parts.length === 0 || parts.every(p => p.type === 'text')) {
    return <>{text}</>;
  }

  return (
    <>
      {parts.map((part, i) =>
        part.type === 'path' ? (
          <button
            key={i}
            onClick={(e) => { e.stopPropagation(); openPath(part.value).catch(() => toast.error(t.workflows.execution.openPathFailed)); }}
            className="underline decoration-dotted underline-offset-2 text-accent hover:text-accent/80 transition-colors cursor-pointer inline"
            title={part.value}
          >
            {part.value}
          </button>
        ) : (
          <span key={i}>{part.value}</span>
        ),
      )}
    </>
  );
}
