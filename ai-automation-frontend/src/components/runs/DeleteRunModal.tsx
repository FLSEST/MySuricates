import { Trash2 } from 'lucide-react';
import { ConfirmModal } from '../ui';
import { useT } from '../../i18n';

/** Asks before a run is deleted for good — from the run list and from the run page (23635ef6). */
export function DeleteRunModal({ open, onClose, onDelete, deleting }: {
  open: boolean;
  onClose: () => void;
  onDelete: () => void;
  deleting: boolean;
}) {
  const t = useT();
  return (
    <ConfirmModal
      open={open}
      onClose={onClose}
      onConfirm={onDelete}
      title={t.runs.delete}
      message={t.runs.deleteConfirm}
      confirmLabel={t.common.delete}
      confirmIcon={<Trash2 className="w-4 h-4" />}
      variant="danger"
      isPending={deleting}
    />
  );
}
