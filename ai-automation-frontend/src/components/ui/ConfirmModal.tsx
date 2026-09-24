import type { ReactNode } from 'react';
import { Modal } from './Modal';
import { Button } from './Button';
import { useT } from '../../i18n';

interface ConfirmModalProps {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  /** Explanation shown as secondary text; for richer content pass children instead (or in addition). */
  message?: ReactNode;
  children?: ReactNode;
  confirmLabel: ReactNode;
  cancelLabel?: string;
  /** `danger` for destructive actions (delete, archive, disconnect). */
  variant?: 'primary' | 'danger';
  /** Locks the confirm button with a spinner while the action runs. */
  isPending?: boolean;
  /** Disables the cancel button, e.g. while an action runs that closing cannot stop (`onClose` should then ignore Escape and backdrop as well). */
  cancelDisabled?: boolean;
  confirmIcon?: ReactNode;
  cancelVariant?: 'secondary' | 'ghost';
  size?: 'sm' | 'md';
  /** Further action shown on the left of the footer, e.g. a way into a stronger variant of the action. */
  extraAction?: ReactNode;
}

/** Yes/no dialog on top of `Modal`: same keyboard handling (Escape closes) and footer layout. */
export function ConfirmModal({
  open,
  onClose,
  onConfirm,
  title,
  message,
  children,
  confirmLabel,
  cancelLabel,
  variant = 'primary',
  isPending = false,
  cancelDisabled = false,
  confirmIcon,
  cancelVariant = 'secondary',
  size = 'sm',
  extraAction,
}: ConfirmModalProps) {
  const t = useT();
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      size={size}
      footer={
        <>
          {extraAction && <div className="mr-auto">{extraAction}</div>}
          <Button variant={cancelVariant} onClick={onClose} disabled={cancelDisabled}>
            {cancelLabel ?? t.common.cancel}
          </Button>
          <Button variant={variant} icon={confirmIcon} onClick={onConfirm} loading={isPending}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {message && <p className="text-sm text-text-secondary">{message}</p>}
      {children}
    </Modal>
  );
}
