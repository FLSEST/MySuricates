import { useState } from 'react';
import { Trash2 } from 'lucide-react';
import { useDeleteRun } from '../../hooks/useWorkflows';
import { useT } from '../../i18n';
import type { WorkflowRunListItem } from '../../types';
import { DeleteRunModal } from './DeleteRunModal';

/** Aktive Läufe lehnt das Backend mit 409 ab — der Knopf bleibt hier gleich gesperrt. */
const AKTIVE_STATUS = ['RUNNING', 'PENDING', 'PAUSED'];

export function DeleteRunButton({ run }: { run: WorkflowRunListItem }) {
  const t = useT();
  const deleteRun = useDeleteRun();
  const [confirming, setConfirming] = useState(false);
  const aktiv = AKTIVE_STATUS.includes(run.status);

  return (
    <>
      <button
        type="button"
        title={t.runs.delete}
        aria-label={t.runs.delete}
        disabled={aktiv || deleteRun.isPending}
        className="p-1.5 rounded text-text-secondary hover:text-danger hover:bg-danger/10
                   disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
        onClick={(e) => {
          e.stopPropagation();
          setConfirming(true);
        }}
      >
        <Trash2 className="w-4 h-4" />
      </button>
      {confirming && (
        // Der Dialog hängt in der Tabellenzeile: seine Klicks dürfen den Lauf nicht öffnen.
        <div onClick={(e) => e.stopPropagation()}>
          <DeleteRunModal
            open
            onClose={() => setConfirming(false)}
            deleting={deleteRun.isPending}
            onDelete={() => deleteRun.mutate(run.id, { onSettled: () => setConfirming(false) })}
          />
        </div>
      )}
    </>
  );
}
