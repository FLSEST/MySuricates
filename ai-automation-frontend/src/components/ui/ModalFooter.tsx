import { Button } from './Button';

interface ModalFooterProps {
  onCancel: () => void;
  onAction: () => void;
  cancelLabel?: string;
  actionLabel: string;
  actionVariant?: 'primary' | 'danger' | 'secondary' | 'ghost';
  actionLoading?: boolean;
  actionDisabled?: boolean;
  cancelVariant?: 'secondary' | 'ghost';
}

export function ModalFooter({
  onCancel,
  onAction,
  cancelLabel,
  actionLabel,
  actionVariant = 'primary',
  actionLoading = false,
  actionDisabled = false,
  cancelVariant = 'secondary',
}: ModalFooterProps) {
  return (
    <>
      <Button variant={cancelVariant} onClick={onCancel}>
        {cancelLabel ?? 'Cancel'}
      </Button>
      <Button
        variant={actionVariant}
        onClick={onAction}
        loading={actionLoading}
        disabled={actionDisabled}
      >
        {actionLabel}
      </Button>
    </>
  );
}
