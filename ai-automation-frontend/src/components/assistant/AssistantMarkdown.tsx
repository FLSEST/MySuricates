import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { isInternalAppPath } from '../../utils/internalAppPath';

function MarkdownLink({ href, children, onNavigate }: {
  href?: string;
  children?: React.ReactNode;
  onNavigate: (path: string) => void;
}) {
  if (isInternalAppPath(href)) {
    return (
      <button
        onClick={() => onNavigate(href)}
        className="text-accent underline underline-offset-2 hover:text-accent-hover transition-colors"
      >
        {children}
      </button>
    );
  }
  return (
    <a href={href} target="_blank" rel="noopener noreferrer"
       className="text-accent underline underline-offset-2 hover:text-accent-hover">
      {children}
    </a>
  );
}

/** Markdown of an assistant answer in the chat bubble; app-internal links navigate in place. */
export function AssistantMarkdown({ content, onNavigate }: {
  content: string;
  onNavigate: (path: string) => void;
}) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        a: ({ href, children }) => (
          <MarkdownLink href={href} onNavigate={onNavigate}>{children}</MarkdownLink>
        ),
        p: ({ children }) => <p className="mb-2 last:mb-0">{children}</p>,
        ul: ({ children }) => <ul className="list-disc list-inside mb-2 space-y-0.5">{children}</ul>,
        ol: ({ children }) => <ol className="list-decimal list-inside mb-2 space-y-0.5">{children}</ol>,
        li: ({ children }) => <li className="text-sm">{children}</li>,
        strong: ({ children }) => <strong className="font-semibold text-text">{children}</strong>,
        code: ({ children }) => (
          <code className="px-1 py-0.5 rounded text-xs bg-black/20 font-mono">{children}</code>
        ),
        pre: ({ children }) => (
          <pre className="mt-1 mb-2 p-2 rounded-lg bg-black/20 text-xs font-mono overflow-x-auto max-h-[200px] overflow-y-auto">{children}</pre>
        ),
        table: ({ children }) => (
          <div className="overflow-x-auto my-2 -mx-1">
            <table className="text-xs border-collapse min-w-full">{children}</table>
          </div>
        ),
        th: ({ children }) => (
          <th className="px-2 py-1 border border-border/30 text-left font-semibold bg-black/10">{children}</th>
        ),
        td: ({ children }) => (
          <td className="px-2 py-1 border border-border/30">{children}</td>
        ),
      }}
    >
      {content}
    </ReactMarkdown>
  );
}
