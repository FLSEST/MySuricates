import { AlertCircle } from 'lucide-react';
import { useT } from '../../i18n';
import type { WorkflowRun } from '../../types';
import { ExecutionRerunButton } from '../workflow/ExecutionRerunButton';
import { failedStep, runErrorText, stepDisplayName } from '../../utils/runResult';
import { ProHandoff } from './ProHandoff';

/**
 * Why a run failed, in plain text (never a JSON dump), at which step, and the two ways on:
 * run it again with the same input, or investigate it in Pro mode.
 */
export function RunFailureCard({ run }: { run: WorkflowRun }) {
  const t = useT();
  const texts = t.simple.result;
  const step = failedStep(run);
  const title = run.status === 'INTERRUPTED' ? texts.interruptedTitle : texts.failedTitle;

  return (
    <section aria-label={title} className="space-y-3" data-testid="run-failure">
      <div role="alert" className="rounded-2xl border border-danger/30 bg-danger-subtle p-[18px]">
        <div className="flex items-start gap-3">
          <AlertCircle className="mt-0.5 h-5 w-5 shrink-0 text-danger" aria-hidden="true" />
          <div className="min-w-0 flex-1">
            <p className="text-base font-semibold text-text">{title}</p>
            {step && (
              <p className="mt-0.5 text-sm text-text-secondary">
                {texts.atStep.replace('{name}', stepDisplayName(step, t))}
              </p>
            )}
            <p className="mt-2 whitespace-pre-wrap break-words text-sm text-text" data-testid="run-failure-reason">
              {runErrorText(run, t)}
            </p>
          </div>
        </div>
        <div className="mt-4 flex justify-end">
          <ExecutionRerunButton run={run} />
        </div>
      </div>
      <ProHandoff
        reason={texts.investigate}
        to={`/workflows/${run.workflowId}/run/${run.id}`}
        action={texts.investigateInPro}
      />
    </section>
  );
}
