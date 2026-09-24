import { useState } from 'react';
import { Card, CardHeader, Button, Input } from '../ui';
import { useUpdateWorkflow } from '../../hooks/useWorkflows';
import { useT } from '../../i18n';

/**
 * Default values for the workflow's `{{ variables }}` (detail sidebar). Saves only filled-in values;
 * a changed `defaultContext` from the server replaces the local input.
 */
export function DefaultContextEditor({
  workflowId,
  variables,
  defaultContext,
}: {
  workflowId: string;
  variables: string[];
  defaultContext: Record<string, string>;
}) {
  const t = useT();
  const updateWorkflow = useUpdateWorkflow();
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(variables.map((v) => [v, defaultContext[v] ?? '']))
  );

  const contextKey = JSON.stringify(defaultContext);
  const [prevContextKey, setPrevContextKey] = useState(contextKey);
  if (contextKey !== prevContextKey) {
    setPrevContextKey(contextKey);
    setValues(Object.fromEntries(variables.map((v) => [v, defaultContext[v] ?? ''])));
  }

  const hasChanges = variables.some((v) => (values[v] ?? '') !== (defaultContext[v] ?? ''));

  const handleSave = () => {
    const ctx: Record<string, string> = {};
    for (const v of variables) {
      if (values[v]?.trim()) {
        ctx[v] = values[v];
      }
    }
    updateWorkflow.mutate({ id: workflowId, data: { defaultContext: ctx } });
  };

  return (
    <Card>
      <CardHeader
        title={t.workflows.defaultValues}
        action={
          hasChanges ? (
            <Button
              size="sm"
              variant="secondary"
              onClick={handleSave}
              loading={updateWorkflow.isPending}
            >
              {t.common.save}
            </Button>
          ) : undefined
        }
      />
      <div className="space-y-3">
        <p className="text-xs text-text-tertiary">{t.workflows.defaultValuesHint}</p>
        {variables.map((variable) => (
          <Input
            key={variable}
            label={variable}
            value={values[variable] ?? ''}
            onChange={(e) => setValues({ ...values, [variable]: e.target.value })}
            placeholder={`${t.workflows.enterValue} ${variable}`}
          />
        ))}
      </div>
    </Card>
  );
}
