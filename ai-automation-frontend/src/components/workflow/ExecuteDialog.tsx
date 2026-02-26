import { useState } from 'react';
import { Modal, Button, Input } from '../ui';
import { useT } from '../../i18n';

interface ExecuteDialogProps {
  open: boolean;
  onClose: () => void;
  onExecute: (context: Record<string, string>) => void;
  variables: string[];
  defaultValues?: Record<string, string>;
  loading?: boolean;
}

export function ExecuteDialog({ open, onClose, onExecute, variables, defaultValues, loading }: ExecuteDialogProps) {
  const t = useT();
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(variables.map((v) => [v, defaultValues?.[v] ?? '']))
  );

  const allFilled = variables.every((v) => values[v]?.trim());

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
        <>
          <Button variant="secondary" onClick={onClose}>
            {t.common.cancel}
          </Button>
          <Button onClick={handleExecute} loading={loading} disabled={!allFilled}>
            {t.workflows.execute}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <p className="text-sm text-text-secondary">{t.workflows.variablesDescription}</p>
        {variables.map((variable) => (
          <Input
            key={variable}
            label={variable}
            value={values[variable] ?? ''}
            onChange={(e) => setValues({ ...values, [variable]: e.target.value })}
            placeholder={defaultValues?.[variable]
              ? `${t.workflows.defaultValueSet}: ${defaultValues[variable]}`
              : `${t.workflows.enterValue} ${variable}`}
          />
        ))}
      </div>
    </Modal>
  );
}
