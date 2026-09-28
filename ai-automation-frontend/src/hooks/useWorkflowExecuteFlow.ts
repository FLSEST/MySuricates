import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useExecuteWorkflow } from './useWorkflows';
import { readContextSchema } from '../utils/contextSchema';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import type { Workflow } from '../types';

interface ExecuteDialogState {
  open: boolean;
  workflow: Workflow | null;
  variables: string[];
}

const CLOSED: ExecuteDialogState = { open: false, workflow: null, variables: [] };

/**
 * Ausführen aus der Workflow-Liste: ohne Variablen sofort, mit Variablen erst über den Dialog
 * (Pflicht/optional, Beschreibungen und Platzhalter aus dem Kontext-Schema); danach zur Laufseite.
 */
export function useWorkflowExecuteFlow() {
  const navigate = useNavigate();
  const executeWorkflow = useExecuteWorkflow();
  const [executeDialog, setExecuteDialog] = useState<ExecuteDialogState>(CLOSED);
  const schema = useMemo(
    () => readContextSchema(executeDialog.workflow),
    [executeDialog.workflow],
  );

  const execute = (workflow: Workflow) => {
    const variables = extractWorkflowVariables(workflow.blocks);
    if (variables.length > 0) {
      setExecuteDialog({ open: true, workflow, variables });
    } else {
      executeWorkflow.mutate(
        { id: workflow.id },
        { onSuccess: (run) => navigate(`/workflows/${workflow.id}/run/${run.id}`) },
      );
    }
  };

  const executeWithContext = (context: Record<string, string>) => {
    if (!executeDialog.workflow) return;
    const workflowId = executeDialog.workflow.id;
    executeWorkflow.mutate(
      { id: workflowId, context },
      {
        onSuccess: (run) => {
          setExecuteDialog(CLOSED);
          navigate(`/workflows/${workflowId}/run/${run.id}`);
        },
      },
    );
  };

  return {
    executeDialog, schema, execute, executeWithContext,
    closeExecuteDialog: () => setExecuteDialog(CLOSED),
    executing: executeWorkflow.isPending,
  };
}
