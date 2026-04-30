import { useState } from 'react';
import { Modal, Input, ModalFooter } from '../ui';
import { useT } from '../../i18n';

interface ExecuteDialogProps {
  open: boolean;
  onClose: () => void;
  onExecute: (context: Record<string, string>) => void;
  variables: string[];
  defaultValues?: Record<string, string>;
  optionalVariables?: string[];
  descriptions?: Record<string, string>;
  placeholders?: Record<string, string>;
  loading?: boolean;
}

export function ExecuteDialog({
  open,
  onClose,
  onExecute,
  variables,
  defaultValues,
  optionalVariables,
  descriptions,
  placeholders,
  loading,
}: ExecuteDialogProps) {
  const t = useT();
  const optionalSet = new Set(optionalVariables ?? []);
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(variables.map((v) => [v, defaultValues?.[v] ?? '']))
  );

  const allFilled = variables.every((v) => optionalSet.has(v) || values[v]?.trim());

  const handleExecute = () => {
    onExecute(values);
  };

  // Reset values when variables change
  const variableKey = variables.join(',');
  const [prevKey, setPrevKey] = useState(variableKey);
  if (variableKey !== prevKey) {
    setPrevKey(variableKey);
    setValues(Object.fromEntries(variables.map((v) => [v, defaultValues?.[v] ?? ''])));
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.workflows.executeWithVariables}
      footer={
        <ModalFooter
          onCancel={onClose}
          onAction={handleExecute}
          cancelLabel={t.common.cancel}
          actionLabel={t.workflows.execute}
          actionLoading={loading}
          actionDisabled={!allFilled}
        />
      }
    >
      <div className="space-y-4">
        <p className="text-sm text-text-secondary">{t.workflows.variablesDescription}</p>
        {variables.map((variable) => {
          const isOptional = optionalSet.has(variable);
          const label = isOptional ? `${variable} (${t.workflows.optional})` : variable;
          const description = descriptions?.[variable];
          const customPlaceholder = placeholders?.[variable];
          const placeholder = customPlaceholder
            ? customPlaceholder
            : defaultValues?.[variable]
              ? `${t.workflows.defaultValueSet}: ${defaultValues[variable]}`
              : `${t.workflows.enterValue} ${variable}`;
          return (
            <div key={variable} className="space-y-1">
              <Input
                label={label}
                value={values[variable] ?? ''}
                onChange={(e) => setValues({ ...values, [variable]: e.target.value })}
                placeholder={placeholder}
              />
              {description && (
                <p className="text-xs text-text-tertiary">{description}</p>
              )}
            </div>
          );
        })}
      </div>
    </Modal>
  );
}
