import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface MarkdownBlockProps {
  children: string;
  /**
   * Drops images (their alt text stays as plain text). For text that may come from outside —
   * AI output with fetched content — an image URL would be loaded as soon as it is shown.
   */
  noImages?: boolean;
}

/** Markdown (GFM) with the app's prose styling. No raw HTML: react-markdown ignores it by default. */
export function MarkdownBlock({ children, noImages = false }: MarkdownBlockProps) {
  return (
    <div className="prose prose-sm dark:prose-invert max-w-none
      prose-headings:text-text prose-headings:mt-3 first:prose-headings:mt-0 prose-p:text-text-secondary
      prose-a:text-accent prose-a:no-underline hover:prose-a:underline
      prose-code:text-accent prose-code:bg-surface-overlay prose-code:px-1 prose-code:py-0.5 prose-code:rounded
      prose-pre:bg-surface-overlay prose-pre:border prose-pre:border-border
      prose-table:border-collapse prose-th:border prose-th:border-border prose-th:bg-surface-overlay
      prose-td:border prose-td:border-border
      prose-strong:text-text prose-li:text-text-secondary prose-hr:border-border">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        disallowedElements={noImages ? ['img'] : undefined}
        unwrapDisallowed={noImages}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
}
