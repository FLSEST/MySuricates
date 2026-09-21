import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface ApprovalQuestionProps {
  question: string;
  /** Tailwind max-height class of the scroll area — long questions carry measurements or AI verdicts. */
  maxHeightClass?: string;
}

/** The question of an approval or human-input step: Markdown, scrollable when long. */
export function ApprovalQuestion({ question, maxHeightClass = 'max-h-[50vh]' }: ApprovalQuestionProps) {
  return (
    <div
      className={`${maxHeightClass} overflow-y-auto rounded-lg border border-border bg-surface p-4`}
      data-testid="approval-question"
    >
      <div className="prose prose-sm dark:prose-invert max-w-none
        prose-headings:text-text prose-headings:mt-3 first:prose-headings:mt-0 prose-p:text-text-secondary
        prose-a:text-accent prose-a:no-underline hover:prose-a:underline
        prose-code:text-accent prose-code:bg-surface-overlay prose-code:px-1 prose-code:py-0.5 prose-code:rounded
        prose-pre:bg-surface-overlay prose-pre:border prose-pre:border-border
        prose-table:border-collapse prose-th:border prose-th:border-border prose-th:bg-surface-overlay
        prose-td:border prose-td:border-border
        prose-strong:text-text prose-li:text-text-secondary prose-hr:border-border">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{question}</ReactMarkdown>
      </div>
    </div>
  );
}
