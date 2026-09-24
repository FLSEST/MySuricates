import { useNavigate } from 'react-router-dom';
import { FileText, FolderOpen, ListChecks } from 'lucide-react';
import { openPath } from '../../api/client';
import { useToast } from '../../hooks/useToast';
import { useT } from '../../i18n';
import type { WorkflowRun } from '../../types';
import { Button } from '../ui';
import { CopyButton } from '../shared/CopyButton';
import { GateStatusBanner } from '../shared/GateStatusBanner';
import { MarkdownBlock } from '../shared/MarkdownBlock';
import { WorkflowResultContent } from '../dashboard/WorkflowResultContent';
import {
  WORKFLOW_RESULT_STATUS_DOT, type WorkflowResult, type WorkflowResultStatus,
} from '../dashboard/workflowWidgetContract';
import {
  createdTodoCount, extractPaths, pickResultStep, readRunReport, reportInvalid, stripMarkerLines,
} from '../../utils/runResult';
import { Disclosure } from './Disclosure';
import { SectionLabel, SimpleCard } from './kit';

const REPORT_STATUS_PILL: Record<WorkflowResultStatus, string> = {
  GREEN: 'bg-success-subtle text-success',
  YELLOW: 'bg-warning-subtle text-warning',
  RED: 'bg-danger-subtle text-danger',
};

const GATE_STATUSES = new Set(['GREEN', 'YELLOW', 'RED']);

/** The run's validated report: title, translated status, KPIs, points and summary. */
export function RunReportCard({ report }: { report: WorkflowResult }) {
  const texts = useT().simple.result;
  return (
    <SimpleCard>
      <div className="flex items-start justify-between gap-3 mb-3">
        <p className="flex items-center gap-2 min-w-0 text-base font-semibold text-text break-words">
          <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${WORKFLOW_RESULT_STATUS_DOT[report.status]}`} aria-hidden="true" />
          {report.title ?? texts.title}
        </p>
        <span className={`shrink-0 rounded-full px-2.5 py-0.5 text-xs font-medium ${REPORT_STATUS_PILL[report.status]}`}>
          {texts.reportStatus[report.status]}
        </span>
      </div>
      <div className="flex flex-col gap-3">
        <WorkflowResultContent result={report} />
      </div>
    </SimpleCard>
  );
}

function OutputBlock({ text }: { text: string }) {
  const texts = useT().simple.result;
  return (
    <div className="relative rounded-xl border border-border bg-surface">
      <div className="absolute right-2 top-2" title={texts.copy}>
        <CopyButton text={text} />
      </div>
      <div className="max-h-[60vh] overflow-y-auto p-4 pr-10" data-testid="run-result-output">
        <MarkdownBlock noImages>{text}</MarkdownBlock>
      </div>
    </div>
  );
}

/**
 * What a finished run resulted in, in the order a newcomer reads it: the quality verdict, the
 * report the run delivered, the output of the step that produced the result (collapsed when a
 * report already says it), files it names, and TODOs it created. Nothing technical — the steps
 * themselves stay in the page's details.
 */
export function RunResultCard({ run }: { run: WorkflowRun }) {
  const t = useT();
  const texts = t.simple.result;
  const navigate = useNavigate();
  const toast = useToast();

  const report = readRunReport(run);
  const invalid = !report && reportInvalid(run);
  const resultStep = pickResultStep(run.steps);
  const output = resultStep ? stripMarkerLines(resultStep.output) : '';
  const paths = extractPaths(output);
  const todoCount = createdTodoCount(run);
  const hasGate = GATE_STATUSES.has(String(run.metadata?.gate_status ?? ''));
  const nothing = !hasGate && !report && !invalid && !output && todoCount === 0;

  return (
    <section aria-label={texts.title} className="space-y-4" data-testid="run-result">
      <GateStatusBanner run={run} />

      {report && <RunReportCard report={report} />}
      {invalid && <p className="text-sm text-warning">{texts.reportInvalid}</p>}

      {output && (report ? (
        <Disclosure label={texts.fullOutput}>
          <OutputBlock text={output} />
        </Disclosure>
      ) : (
        <div>
          <SectionLabel>{texts.output}</SectionLabel>
          <OutputBlock text={output} />
        </div>
      ))}

      {paths.length > 0 && (
        <div>
          <SectionLabel>{texts.files}</SectionLabel>
          <ul className="space-y-2">
            {paths.map((path) => (
              <li key={path} className="flex items-center justify-between gap-3 rounded-xl border border-border bg-surface px-4 py-2.5">
                <span className="flex min-w-0 items-center gap-2 text-sm text-text">
                  <FileText className="h-4 w-4 shrink-0 text-text-tertiary" aria-hidden="true" />
                  <span className="truncate font-mono text-xs" title={path}>{path}</span>
                </span>
                <Button
                  variant="secondary"
                  size="sm"
                  icon={<FolderOpen className="h-3.5 w-3.5" />}
                  aria-label={`${texts.open}: ${path}`}
                  onClick={() => openPath(path).catch(() => toast.error(t.workflows.execution.openPathFailed))}
                >
                  {texts.open}
                </Button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {todoCount > 0 && (
        <div className="flex items-center justify-between gap-3 rounded-xl border border-border bg-surface px-4 py-3">
          <span className="flex items-center gap-2 text-sm text-text">
            <ListChecks className="h-4 w-4 text-text-tertiary" aria-hidden="true" />
            {todoCount === 1 ? texts.todosCreatedOne : texts.todosCreatedMany.replace('{n}', String(todoCount))}
          </span>
          <Button variant="ghost" size="sm" onClick={() => navigate('/todos')}>
            {texts.showTodos}
          </Button>
        </div>
      )}

      {nothing && <p className="text-sm text-text-secondary">{texts.empty}</p>}
    </section>
  );
}
