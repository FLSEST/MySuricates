import { useState } from 'react';
import { Button, Input } from '../ui';
import { useT } from '../../i18n';
import type { PendingHumanInput } from '../../types';

interface HumanInputDialogProps {
  humanInput: PendingHumanInput;
  onSubmit: (value: string) => void;
  loading?: boolean;
}

export function HumanInputDialog({ humanInput, onSubmit, loading }: HumanInputDialogProps) {
  const t = useT();
  const [value, setValue] = useState(humanInput.defaultValue || '');
  const [error, setError] = useState<string | null>(null);

  const validate = (): boolean => {
    if (humanInput.required && !value.trim()) {
      setError(t.workflows.execution.humanInput.requiredError);
      return false;
    }
    if (humanInput.validationPattern) {
      try {
        const regex = new RegExp(humanInput.validationPattern);
        if (!regex.test(value)) {
          setError(t.workflows.execution.humanInput.validationError);
          return false;
        }
      } catch {
        // Invalid regex pattern — skip validation
      }
    }
    setError(null);
    return true;
  };

  const handleSubmit = () => {
    if (validate()) {
      onSubmit(value);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 animate-fade-in">
      <div className="bg-surface-raised border border-border rounded-2xl shadow-xl p-6 max-w-md w-full mx-4">
        <h3 className="text-lg font-bold text-text mb-1">
          {t.workflows.execution.humanInput.title}
        </h3>
        <p className="text-sm text-text-secondary mb-4">
          {t.workflows.execution.humanInput.description}
        </p>

        <div className="bg-surface rounded-lg p-4 mb-4 border border-border">
          <p className="text-sm font-medium text-text">{humanInput.prompt}</p>
          <p className="text-xs text-text-tertiary mt-1">Step {humanInput.stepIndex + 1}</p>
        </div>

        <div className="mb-4">
          {humanInput.inputType === 'select' ? (
            <select
              value={value}
              onChange={(e) => { setValue(e.target.value); setError(null); }}
              className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm focus:outline-none focus:ring-2 focus:ring-accent focus:border-transparent"
            >
              <option value="">{t.workflows.execution.humanInput.placeholder}</option>
              {humanInput.options.map((opt) => (
                <option key={opt} value={opt}>{opt}</option>
              ))}
            </select>
          ) : humanInput.inputType === 'textarea' ? (
            <textarea
              value={value}
              onChange={(e) => { setValue(e.target.value); setError(null); }}
              placeholder={t.workflows.execution.humanInput.placeholder}
              rows={4}
              className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm focus:outline-none focus:ring-2 focus:ring-accent focus:border-transparent resize-y"
              onKeyDown={(e) => {
                if (e.key === 'Enter' && e.metaKey) handleSubmit();
              }}
            />
          ) : (
            <Input
              type={humanInput.inputType === 'number' ? 'number' : 'text'}
              value={value}
              onChange={(e) => { setValue(e.target.value); setError(null); }}
              placeholder={t.workflows.execution.humanInput.placeholder}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleSubmit();
              }}
            />
          )}
          {error && (
            <p className="text-xs text-danger mt-1.5">{error}</p>
          )}
        </div>

        <div className="flex justify-end">
          <Button
            onClick={handleSubmit}
            loading={loading}
            disabled={humanInput.required && !value.trim()}
          >
            {t.workflows.execution.humanInput.submit}
          </Button>
        </div>
      </div>
    </div>
  );
}
