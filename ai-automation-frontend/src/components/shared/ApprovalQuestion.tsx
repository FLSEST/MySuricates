import { MarkdownBlock } from './MarkdownBlock';

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
      <MarkdownBlock>{question}</MarkdownBlock>
    </div>
  );
}
