import { useNavigate } from 'react-router-dom';
import {
  Factory, CheckCircle2, XCircle, Clock, ArrowUpRight, RotateCcw, Award, Package,
} from 'lucide-react';
import { Card, Button, Spinner, Badge } from '../ui';
import { useT } from '../../i18n';
import { formatDuration } from '../../utils/formatTime';
import type { Workflow, WorkflowFactoryResult } from '../../types';

/** A workflow to export to the pool, as the export dialog needs it. */
export interface FactoryExportTarget {
  workflowId: string;
  name: string;
  description?: string | null;
}

// Same tones as the status badge: promoted = warning, exported = accent
function StatusIcon({ status }: { status: string }) {
  switch (status) {
    case 'VERIFIED': return <CheckCircle2 className="w-5 h-5 text-success" />;
    case 'FAILED': return <XCircle className="w-5 h-5 text-danger" />;
    case 'CREATED': return <Clock className="w-5 h-5 text-info" />;
    case 'PROMOTED': return <Award className="w-5 h-5 text-warning" />;
    case 'EXPORTED': return <Package className="w-5 h-5 text-accent" />;
    default: return <Spinner size="sm" />;
  }
}

function ResultStatusBadge({ status }: { status: string }) {
  const variant = status === 'VERIFIED' ? 'success'
    : status === 'PROMOTED' ? 'warning'
    : status === 'FAILED' ? 'danger'
    : status === 'EXPORTED' ? 'accent'
    : 'default';
  return <Badge variant={variant} dot>{status}</Badge>;
}

/**
 * Results of the Workflow Factory: this session's results first, then earlier factory-generated
 * workflows that are not among them. Verify (test instance up) and promote act on the workflow.
 */
export function FactoryResultsList({
  results, factoryWorkflows, testHealthy, verifying, promoting, onVerify, onPromote, onExport,
}: {
  results: WorkflowFactoryResult[];
  factoryWorkflows: Workflow[];
  testHealthy: boolean;
  verifying: boolean;
  promoting: boolean;
  onVerify: (workflowId: string) => void;
  onPromote: (workflowId: string) => void;
  onExport: (target: FactoryExportTarget) => void;
}) {
  const t = useT();
  const navigate = useNavigate();

  return (
    <div>
      <h2 className="text-lg font-semibold text-text-primary mb-3">{t.factory.recentRuns}</h2>
      {results.length === 0 && factoryWorkflows.length === 0 ? (
        <Card className="p-8 text-center text-text-secondary">
          <Factory className="w-10 h-10 mx-auto mb-3 opacity-40" />
          <p>{t.factory.noRuns}</p>
        </Card>
      ) : (
        <div className="space-y-2">
          {results.map((r, i) => (
            <Card key={`result-${i}`} className="p-4 flex items-center gap-4">
              <StatusIcon status={r.status} />
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="font-medium text-text-primary truncate">
                    {r.workflowName ?? 'Unknown'}
                  </span>
                  <ResultStatusBadge status={r.status} />
                </div>
                {r.testError && (
                  <p className="text-xs text-danger mt-0.5 truncate">{r.testError}</p>
                )}
                {r.retryErrors.length > 0 && (
                  <p className="text-xs text-warning mt-0.5">
                    {r.retryErrors.length} retry error(s)
                  </p>
                )}
              </div>
              {r.durationMs > 0 && (
                <span className="text-xs text-text-secondary">{formatDuration(r.durationMs)}</span>
              )}
              <div className="flex gap-1">
                {r.workflowId && (
                  <Button
                    size="sm" variant="ghost"
                    onClick={() => onExport({
                      workflowId: r.workflowId!,
                      name: r.workflowName ?? '',
                      description: null,
                    })}
                    title={t.factory.exportToPool}
                  >
                    <Package className="w-3.5 h-3.5" />
                  </Button>
                )}
                {r.workflowId && r.status !== 'VERIFIED' && r.status !== 'PROMOTED' && testHealthy && (
                  <Button
                    size="sm" variant="ghost"
                    onClick={() => onVerify(r.workflowId!)}
                    disabled={verifying}
                    title={t.factory.verifyExisting}
                  >
                    <RotateCcw className="w-3.5 h-3.5" />
                  </Button>
                )}
                {r.workflowId && r.status === 'VERIFIED' && (
                  <Button
                    size="sm" variant="ghost"
                    onClick={() => onPromote(r.workflowId!)}
                    disabled={promoting}
                    title={t.factory.promoteTemplate}
                  >
                    <Award className="w-3.5 h-3.5" />
                  </Button>
                )}
                {r.workflowId && (
                  <Button
                    size="sm" variant="ghost"
                    onClick={() => navigate(`/workflows/${r.workflowId}`)}
                    title="Open workflow"
                  >
                    <ArrowUpRight className="w-3.5 h-3.5" />
                  </Button>
                )}
              </div>
            </Card>
          ))}

          {/* Also show factory-generated workflows not in current session results */}
          {factoryWorkflows
            .filter(fw => !results.some(r => r.workflowId === fw.id))
            .map(fw => (
              <Card key={fw.id} className="p-4 flex items-center gap-4">
                <StatusIcon status={fw.testStatus === 'PASSED' ? 'VERIFIED' : fw.testStatus === 'FAILED' ? 'FAILED' : 'CREATED'} />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-text-primary truncate">{fw.name}</span>
                    <Badge variant="default">{fw.projectName}</Badge>
                    {fw.testStatus && <ResultStatusBadge status={fw.testStatus === 'PASSED' ? 'VERIFIED' : fw.testStatus} />}
                  </div>
                </div>
                <div className="flex gap-1">
                  <Button
                    size="sm" variant="ghost"
                    onClick={() => onExport({
                      workflowId: fw.id,
                      name: fw.name,
                      description: fw.description,
                    })}
                    title={t.factory.exportToPool}
                  >
                    <Package className="w-3.5 h-3.5" />
                  </Button>
                  {testHealthy && (
                    <Button
                      size="sm" variant="ghost"
                      onClick={() => onVerify(fw.id)}
                      disabled={verifying}
                      title={t.factory.verifyExisting}
                    >
                      <RotateCcw className="w-3.5 h-3.5" />
                    </Button>
                  )}
                  <Button
                    size="sm" variant="ghost"
                    onClick={() => navigate(`/workflows/${fw.id}`)}
                    title="Open workflow"
                  >
                    <ArrowUpRight className="w-3.5 h-3.5" />
                  </Button>
                </div>
              </Card>
            ))}
        </div>
      )}
    </div>
  );
}
