import { useMemo, useState } from 'react';
import { Button, Input } from '../ui';
import { useT } from '../../i18n';
import type { HumanInputField, PendingHumanInput } from '../../types';
import { ApprovalQuestion } from './ApprovalQuestion';

interface HumanInputDialogProps {
  humanInput: PendingHumanInput;
  onSubmit: (value: string) => void;
  loading?: boolean;
}

export function HumanInputDialog({ humanInput, onSubmit, loading }: HumanInputDialogProps) {
  const t = useT();
  const isMulti = !!humanInput.fields && humanInput.fields.length > 0;

  const initialValues = useMemo(() => {
    if (isMulti) {
      const acc: Record<string, string> = {};
      for (const f of humanInput.fields ?? []) {
        acc[f.variable_name] = f.default_value ?? '';
      }
      return acc;
    }
    return { [humanInput.variableName]: humanInput.defaultValue || '' };
  }, [humanInput, isMulti]);

  const [values, setValues] = useState<Record<string, string>>(initialValues);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const validate = (): boolean => {
    if (isMulti) {
      const errs: Record<string, string> = {};
      for (const f of humanInput.fields ?? []) {
        const value = values[f.variable_name] ?? '';
        const required = f.required !== false;
        if (required && !String(value).trim()) {
          errs[f.variable_name] = t.workflows.execution.humanInput.requiredError;
          continue;
        }
        if (f.validation_pattern && value) {
          try {
            const regex = new RegExp(f.validation_pattern);
            if (!regex.test(String(value))) {
              errs[f.variable_name] = t.workflows.execution.humanInput.validationError;
            }
          } catch {
            // Invalid regex — skip
          }
        }
      }
      setFieldErrors(errs);
      return Object.keys(errs).length === 0;
    }

    const single = values[humanInput.variableName] ?? '';
    if (humanInput.required && !single.trim()) {
      setError(t.workflows.execution.humanInput.requiredError);
      return false;
    }
    if (humanInput.validationPattern) {
      try {
        const regex = new RegExp(humanInput.validationPattern);
        if (!regex.test(single)) {
          setError(t.workflows.execution.humanInput.validationError);
          return false;
        }
      } catch {
        // Invalid regex — skip
      }
    }
    setError(null);
    return true;
  };

  const handleSubmit = () => {
    if (!validate()) return;
    if (isMulti) {
      onSubmit(JSON.stringify(values));
    } else {
      onSubmit(values[humanInput.variableName] ?? '');
    }
  };

  const updateField = (key: string, value: string) => {
    setValues((prev) => ({ ...prev, [key]: value }));
    setError(null);
    setFieldErrors((prev) => {
      if (!(key in prev)) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  const submitDisabled = isMulti
    ? (humanInput.fields ?? []).some((f) => f.required !== false && !String(values[f.variable_name] ?? '').trim())
    : humanInput.required && !String(values[humanInput.variableName] ?? '').trim();

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 animate-fade-in">
      <div className="bg-surface-raised border border-border rounded-2xl shadow-xl p-6 max-w-2xl w-full mx-4 max-h-[90vh] overflow-y-auto">
        <h3 className="text-lg font-bold text-text mb-1">
          {t.workflows.execution.humanInput.title}
        </h3>
        <p className="text-sm text-text-secondary mb-4">
          {t.workflows.execution.humanInput.description}
        </p>

        <div className="mb-4">
          <ApprovalQuestion question={humanInput.prompt} maxHeightClass="max-h-[40vh]" />
          <p className="text-xs text-text-tertiary mt-1">{t.common.stepNumber.replace('{n}', String(humanInput.stepIndex + 1))}</p>
        </div>

        {isMulti ? (
          <div className="space-y-4 mb-4">
            {(humanInput.fields ?? []).map((field) => (
              <FieldRow
                key={field.variable_name}
                field={field}
                value={values[field.variable_name] ?? ''}
                error={fieldErrors[field.variable_name]}
                onChange={(v) => updateField(field.variable_name, v)}
              />
            ))}
          </div>
        ) : (
          <div className="mb-4">
            <SingleInput
              inputType={humanInput.inputType}
              options={humanInput.options}
              value={values[humanInput.variableName] ?? ''}
              placeholder={t.workflows.execution.humanInput.placeholder}
              onChange={(v) => updateField(humanInput.variableName, v)}
              onSubmitOnEnter={handleSubmit}
            />
            {error && <p className="text-xs text-danger mt-1.5">{error}</p>}
          </div>
        )}

        <div className="flex justify-end">
          <Button onClick={handleSubmit} loading={loading} disabled={submitDisabled}>
            {t.workflows.execution.humanInput.submit}
          </Button>
        </div>
      </div>
    </div>
  );
}

function FieldRow({
  field,
  value,
  error,
  onChange,
}: {
  field: HumanInputField;
  value: string;
  error?: string;
  onChange: (value: string) => void;
}) {
  const t = useT();
  const required = field.required !== false;
  const label = field.label ?? field.variable_name;
  const placeholder = field.placeholder ?? t.workflows.execution.humanInput.placeholder;

  return (
    <div>
      <label className="block text-sm font-medium text-text mb-1">
        {label}
        {!required && <span className="text-text-tertiary text-xs ml-1">({t.workflows.optional})</span>}
      </label>
      <SingleInput
        inputType={field.input_type ?? 'text'}
        options={field.options ?? []}
        value={value}
        placeholder={placeholder}
        onChange={onChange}
      />
      {field.description && !error && (
        <p className="text-xs text-text-tertiary mt-1">{field.description}</p>
      )}
      {error && <p className="text-xs text-danger mt-1">{error}</p>}
    </div>
  );
}

function SingleInput({
  inputType,
  options,
  value,
  placeholder,
  onChange,
  onSubmitOnEnter,
}: {
  inputType: string;
  options: string[];
  value: string;
  placeholder: string;
  onChange: (value: string) => void;
  onSubmitOnEnter?: () => void;
}) {
  if (inputType === 'select') {
    return (
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm focus:outline-none focus:ring-2 focus:ring-accent focus:border-transparent"
      >
        <option value="">{placeholder}</option>
        {options.map((opt) => (
          <option key={opt} value={opt}>{opt}</option>
        ))}
      </select>
    );
  }
  if (inputType === 'textarea') {
    return (
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        rows={4}
        className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm focus:outline-none focus:ring-2 focus:ring-accent focus:border-transparent resize-y"
        onKeyDown={(e) => {
          if (e.key === 'Enter' && e.metaKey && onSubmitOnEnter) onSubmitOnEnter();
        }}
      />
    );
  }
  if (inputType === 'checkbox') {
    return (
      <label className="flex items-center gap-2 text-sm text-text">
        <input
          type="checkbox"
          checked={value === 'true'}
          onChange={(e) => onChange(e.target.checked ? 'true' : 'false')}
          className="rounded border-border"
        />
        <span className="text-text-secondary">{placeholder}</span>
      </label>
    );
  }
  if (inputType === 'date') {
    return (
      <Input
        type="date"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
      />
    );
  }
  return (
    <Input
      type={inputType === 'number' ? 'number' : 'text'}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      onKeyDown={(e) => {
        if (e.key === 'Enter' && onSubmitOnEnter) onSubmitOnEnter();
      }}
    />
  );
}
