import { Lock, LockOpen } from 'lucide-react';
import { ConfirmModal } from '../ui';
import { useT } from '../../i18n';

/**
 * Asks before a workflow is sealed or unsealed — from the header and from the seal banner (23635ef6).
 * `lock` is what the confirmation does: true seals, false unseals; null keeps the dialog closed.
 */
export function SealConfirmModal({ lock, pending, onClose, onConfirm }: {
  lock: boolean | null;
  pending: boolean;
  onClose: () => void;
  onConfirm: (lock: boolean) => void;
}) {
  const t = useT();
  const label = lock ? t.workflows.seal : t.workflows.unseal;
  return (
    <ConfirmModal
      open={lock !== null}
      onClose={onClose}
      onConfirm={() => lock !== null && onConfirm(lock)}
      title={label}
      message={lock ? t.workflows.sealConfirm : t.workflows.unsealConfirm}
      confirmLabel={label}
      confirmIcon={lock ? <Lock className="w-4 h-4" /> : <LockOpen className="w-4 h-4" />}
      isPending={pending}
    />
  );
}
