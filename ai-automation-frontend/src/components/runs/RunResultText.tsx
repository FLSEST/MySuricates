import type { WorkflowRunListItem } from '../../types';

export function RunResultText({ run }: { run: WorkflowRunListItem }) {
  // Die Liste liefert keine Schritte mehr, sondern die gekuerzte Ausgabe des letzten
  // (MySuricates-TODO a05c9504) — der Volltext steht in der Run-Detailansicht.
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
