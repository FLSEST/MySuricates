import type { Locale, TranslationKeys } from '../i18n';
import type { CreateTodoRequest, Workflow, WorkflowRun } from '../types';
import { formatTimestamp } from './localeFormat';
import { stepBlockType } from '../components/workflow/stepMetadata';

/**
 * TODO for a failed run („Todo erstellen“ on the run page), in the app language:
 * title with the workflow name, description with workflow, link to the run, the first
 * failed step and the error messages. Priority HIGH, tag `failed-run`, source WORKFLOW.
 */
export function buildFailedRunTodo(
  run: WorkflowRun,
  workflow: Pick<Workflow, 'name' | 'projectId'> | undefined,
  workflowId: string,
  t: TranslationKeys,
  locale: Locale,
): CreateTodoRequest {
  const text = t.workflows.execution.failedTodo;
  const name = workflow?.name || run.workflowName;
  const failedStep = run.steps?.find(s => s.status === 'FAILED');
  const stepInfo = failedStep
    ? text.stepInfo
      .replace('{n}', () => String(failedStep.stepIndex + 1))
      .replace('{type}', () => stepBlockType(failedStep))
    : null;

  const descParts: string[] = [];
  descParts.push(`**${text.workflow}:** ${name}`);
  descParts.push(`**${text.run}:** [${formatTimestamp(run.startedAt, locale)}](/workflows/${workflowId}/run/${run.id})`);
  if (stepInfo) descParts.push(`**${text.failedStep}:** ${stepInfo}`);
  if (run.error?.message) descParts.push(`**${text.error}:** ${run.error.message}`);
  if (failedStep?.error) descParts.push(`**${text.stepError}:** ${failedStep.error}`);

  return {
    title: text.title.replace('{workflow}', () => name),
    description: descParts.join('\n'),
    priority: 'HIGH',
    tags: ['failed-run'],
    projectId: workflow?.projectId || undefined,
    source: 'WORKFLOW',
    workflowRunId: run.id,
  };
}
