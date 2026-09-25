import { Hand } from 'lucide-react';
import { useT } from '../../i18n';

/**
 * Brings back an approval that was closed without an answer — the run still waits for it. Coloured as a
 * decision of a person (`human` tokens), like the waiting approvals in the sidebar.
 */
export function OpenApprovalButton({ onClick }: { onClick: () => void }) {
  const t = useT();
  return (
    <button
      type="button"
      onClick={onClick}
      className="btn-base px-3 py-1.5 text-xs shrink-0 border border-human/40 bg-human-subtle text-human hover:border-human"
    >
      <Hand className="w-4 h-4" />
      {t.workflows.execution.approval.reopen}
    </button>
  );
}
