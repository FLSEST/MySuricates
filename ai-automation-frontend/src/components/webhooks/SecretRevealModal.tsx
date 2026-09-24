import type { ReactNode } from 'react';
import { Button, Modal } from '../ui';
import { CopyButton } from '../shared/CopyButton';
import { useT } from '../../i18n';

export interface RevealedValue {
  /** Caption above the value; a single value can stand without one. */
  label?: string;
  value: string;
  testId?: string;
}

/**
 * Shows credentials exactly once — a webhook's URL and token after creating or regenerating it, a
 * signing secret after setting it. Every value has a copy button; the server never returns them in
 * full again, so the caller drops them on close. `values === null` keeps the dialog closed.
 */
export function SecretRevealModal({ title, values, onClose, hint, children }: {
  title: string;
  values: RevealedValue[] | null;
  onClose: () => void;
  /** Shown above the values. */
  hint?: string;
  /** Shown below the values, e.g. how to use them. */
  children?: ReactNode;
}) {
  const t = useT();
  return (
    <Modal
      open={values !== null}
      onClose={onClose}
      title={title}
      footer={<Button onClick={onClose}>{t.common.close}</Button>}
    >
      {values && (
        <div className="space-y-4">
          {hint && <p className="text-sm text-text-secondary">{hint}</p>}
          {values.map((value, i) => <RevealedValueRow key={i} {...value} />)}
          {children}
        </div>
      )}
    </Modal>
  );
}

function RevealedValueRow({ label, value, testId }: RevealedValue) {
  const row = (
    <div className="flex items-center gap-2 min-w-0">
      <span className="font-mono text-xs text-text break-all" data-testid={testId}>
        {value}
      </span>
      <CopyButton text={value} />
    </div>
  );
  if (!label) return row;
  return (
    <div>
      <label className="block text-xs font-medium text-text-tertiary mb-1">
        {label}
      </label>
      {row}
    </div>
  );
}
