import type { WorkflowRunListItem } from '../../types';

export function RunResultText({ run }: { run: WorkflowRunListItem }) {
  // The list no longer delivers steps, but the shortened output of the last one
  // (MySuricates TODO a05c9504) - the full text is in the run detail view.
  const isError = !!run.error?.message;
  const text = run.error?.message || run.lastStepPreview || '-';

  if (text === '-') return <span className="text-text-secondary">-</span>;

  return (
    <span
      className={`text-xs block truncate max-w-[300px] ${isError ? 'text-danger' : 'text-text-secondary'}`}
      title={text}
    >
      {text}
    </span>
  );
}
