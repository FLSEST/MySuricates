import { Hint } from '../components/hints/Hint';
import { useState } from 'react';
import { Factory } from 'lucide-react';
import { useFactoryVerify, useFactoryPromote } from '../hooks/useWorkflowFactory';
import { useFactoryForm } from '../hooks/useFactoryForm';
import { useWorkflows } from '../hooks/useWorkflows';
import { useTestingStatus } from '../hooks/useTesting';
import { useT } from '../i18n';
import { useToast } from '../hooks/useToast';
import { apiErrorMessage } from '../api/client';
import { ExportToPoolModal } from '../components/factory/ExportToPoolModal';
import { FactoryFlowDiagram } from '../components/factory/FactoryFlowDiagram';
import { FactoryCreateForm } from '../components/factory/FactoryCreateForm';
import { FactoryResultsList, type FactoryExportTarget } from '../components/factory/FactoryResultsList';
import type { WorkflowFactoryResult } from '../types';

export function WorkflowFactory() {
  const t = useT();
  const toast = useToast();
  const { data: workflows } = useWorkflows();
  const { data: testingStatus } = useTestingStatus();
  const verifyMutation = useFactoryVerify();
  const promoteMutation = useFactoryPromote();

  const [results, setResults] = useState<WorkflowFactoryResult[]>([]);
  const [exportModal, setExportModal] = useState<FactoryExportTarget | null>(null);

  const isTestHealthy = testingStatus?.healthy ?? false;
  const form = useFactoryForm({
    testHealthy: isTestHealthy,
    onResult: (result) => setResults(prev => [result, ...prev]),
  });

  // A new result for a workflow replaces its entry of this session, otherwise it comes first
  const upsertResult = (workflowId: string, result: WorkflowFactoryResult) => {
    setResults(prev => {
      const idx = prev.findIndex(r => r.workflowId === workflowId);
      if (idx >= 0) { const next = [...prev]; next[idx] = result; return next; }
      return [result, ...prev];
    });
  };

  const handleVerify = (workflowId: string) => {
    verifyMutation.mutate(workflowId, {
      onSuccess: (result) => {
        upsertResult(workflowId, result);
        if (result.status === 'VERIFIED') {
          toast.success(t.factory.verified);
        } else {
          toast.error(t.factory.verifyFailed.replace('{error}', () => result.testError ?? t.factory.unknownError));
        }
      },
      onError: (err) => toast.error(apiErrorMessage(err, t.factory.verifyRequestFailed)),
    });
  };

  const handlePromote = (workflowId: string) => {
    promoteMutation.mutate(workflowId, {
      onSuccess: (result) => {
        upsertResult(workflowId, result);
        toast.success(t.factory.promoted);
      },
      onError: (err) => toast.error(apiErrorMessage(err, t.factory.promoteFailed)),
    });
  };

  const handleExported = (workflowId: string) => {
    setResults(prev => prev.map(r =>
      r.workflowId === workflowId ? { ...r, status: 'EXPORTED' } : r
    ));
  };

  // Workflows that were factory-generated
  const factoryWorkflows = workflows?.filter(w =>
    (w.metadata as Record<string, unknown>)?.factoryGenerated === true
  ) ?? [];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <div className="flex items-center gap-3">
          <Factory className="w-7 h-7 text-accent" />
          <h1 className="text-2xl font-bold text-text">{t.factory.title}</h1>
        </div>
        <p className="text-sm text-text-secondary mt-1">{t.factory.subtitle}</p>
      </div>

      <Hint id="factory-create" />

      <FactoryFlowDiagram
        autoFix={form.autoFix}
        autoPromote={form.autoPromote}
        onAutoFixChange={form.setAutoFix}
        onAutoPromoteChange={form.setAutoPromote}
        maxRetries={form.maxRetries}
        testHealthy={isTestHealthy}
      />

      <FactoryCreateForm form={form} />

      <FactoryResultsList
        results={results}
        factoryWorkflows={factoryWorkflows}
        testHealthy={isTestHealthy}
        verifying={verifyMutation.isPending}
        promoting={promoteMutation.isPending}
        onVerify={handleVerify}
        onPromote={handlePromote}
        onExport={setExportModal}
      />

      {/* Export Modal */}
      {exportModal && (
        <ExportToPoolModal
          open={!!exportModal}
          onClose={() => setExportModal(null)}
          workflowId={exportModal.workflowId}
          workflowName={exportModal.name}
          workflowDescription={exportModal.description}
          onExported={() => handleExported(exportModal.workflowId)}
        />
      )}
    </div>
  );
}
