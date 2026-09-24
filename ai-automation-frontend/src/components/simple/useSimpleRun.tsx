import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useT } from '../../i18n';
import { ExecuteDialog } from '../workflow/ExecuteDialog';
import { useExecuteWorkflow } from '../../hooks/useWorkflows';
import { extractWorkflowVariables } from '../../utils/extractWorkflowVariables';
import { readContextSchema } from '../../utils/contextSchema';
import type { Workflow } from '../../types';

/**
 * Starting a workflow in Simple mode: without inputs it starts right away, otherwise the input
 * dialog asks first. Either way the run page opens once the run exists.
 * Render `dialog` once in the page; call `run(workflow)` from any start button.
 */
export function useSimpleRun() {
  const t = useT();
  const navigate = useNavigate();
  const executeWorkflow = useExecuteWorkflow();
  const [asking, setAsking] = useState<{ workflow: Workflow; variables: string[] } | null>(null);

  const start = (workflowId: string, context?: Record<string, string>) => {
    executeWorkflow.mutate(
      { id: workflowId, context },
      {
        onSuccess: (run) => {
          setAsking(null);
          navigate(`/workflows/${workflowId}/run/${run.id}`);
        },
      },
    );
  };

  const run = (workflow: Workflow) => {
    const variables = extractWorkflowVariables(workflow.blocks);
    if (variables.length > 0) {
      setAsking({ workflow, variables });
    } else {
      start(workflow.id);
    }
  };

  const schema = readContextSchema(asking?.workflow);
  const dialog = (
    <ExecuteDialog
      open={asking !== null}
      onClose={() => setAsking(null)}
      onExecute={(context) => { if (asking) start(asking.workflow.id, context); }}
      variables={asking?.variables ?? []}
      defaultValues={asking?.workflow.defaultContext}
      optionalVariables={schema.optional}
      descriptions={schema.descriptions}
      placeholders={schema.placeholders}
      loading={executeWorkflow.isPending}
      humanizeLabels
      title={t.simple.run.inputsTitle}
    />
  );

  return { run, dialog, isPending: executeWorkflow.isPending };
}
