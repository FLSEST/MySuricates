import { Trash2 } from 'lucide-react';
import { useDeleteRun } from '../../hooks/useWorkflows';
import { useT } from '../../i18n';
import type { WorkflowRunListItem } from '../../types';

/** Aktive Läufe lehnt das Backend mit 409 ab — der Knopf bleibt hier gleich gesperrt. */
const AKTIVE_STATUS = ['RUNNING', 'PENDING', 'PAUSED'];

export function DeleteRunButton({ run }: { run: WorkflowRunListItem }) {
  const t = useT();
  const deleteRun = useDeleteRun();
  const aktiv = AKTIVE_STATUS.includes(run.status);

  return (
    <button
      type="button"
      title={t.runs.delete}
      aria-label={t.runs.delete}
      disabled={aktiv || deleteRun.isPending}
      className="p-1.5 rounded text-text-secondary hover:text-danger hover:bg-danger/10
                 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
      onClick={(e) => {
        e.stopPropagation();
        if (window.confirm(t.runs.deleteConfirm)) deleteRun.mutate(run.id);
      }}
    >
      <Trash2 className="w-4 h-4" />
    </button>
  );
}
