import {
  ArrowLeft, Play, Pencil, Wand2, Wrench, Copy, Download, FileCode2, Trash2, Lock, LockOpen,
} from 'lucide-react';
import { Button } from '../ui';
import type { Workflow } from '../../types';
import { exportWorkflow, useSealWorkflow } from '../../hooks/useWorkflows';
import { apiErrorMessage, fetchWorkflowBpmn } from '../../api/client';
import { downloadBlob } from '../../utils/downloadFile';
import { useToast } from '../../hooks/useToast';
import { useModuleEnabled } from '../../hooks/useModules';

interface WorkflowDetailHeaderProps {
  workflow: Workflow;
  t: ReturnType<typeof import('../../i18n').useT>;
  onNavigateBack: () => void;
  onEdit: () => void;
  onExecute: () => void;
  onOptimize: () => void;
  onRepair: () => void;
  onClone: () => void;
  onDelete: () => void;
  executeLoading: boolean;
  optimizeLoading: boolean;
  repairLoading: boolean;
  hasFailedRuns: boolean;
}

export function WorkflowDetailHeader({
  workflow,
  t,
  onNavigateBack,
  onEdit,
  onExecute,
  onOptimize,
  onRepair,
  onClone,
  onDelete,
  executeLoading,
  optimizeLoading,
  repairLoading,
  hasFailedRuns,
}: WorkflowDetailHeaderProps) {
  const toast = useToast();
  const factoryModule = useModuleEnabled('factory');
  const bpmnModule = useModuleEnabled('bpmn');
  const sealingModule = useModuleEnabled('sealing');
  const sealWorkflow = useSealWorkflow();
  const sealed = !!workflow.sealed;
  const toggleSeal = () => {
    if (confirm(sealed ? t.workflows.unsealConfirm : t.workflows.sealConfirm)) {
      sealWorkflow.mutate({ id: workflow.id, lock: !sealed });
    }
  };

  const handleBpmnExport = async () => {
    try {
      const xml = await fetchWorkflowBpmn(workflow.id);
      const safeName = workflow.name.replace(/[^a-zA-Z0-9_-]/g, '_');
      downloadBlob(new Blob([xml], { type: 'application/xml' }), `${safeName}.bpmn`);
    } catch (err) {
      toast.error(apiErrorMessage(err, t.workflows.bpmnExportFailed));
    }
  };

  return (
    <div className="flex items-center gap-4">
      <button
        onClick={onNavigateBack}
        className="p-2 rounded-lg hover:bg-surface-raised text-text-secondary hover:text-text transition-colors"
        aria-label={t.common.back}
      >
        <ArrowLeft className="w-5 h-5" />
      </button>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-3">
          <span className="text-2xl">{workflow.icon || '📋'}</span>
          <h1 className="text-2xl font-bold text-text truncate">{workflow.name}</h1>
          {sealed && (
            <span
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-accent/15 text-accent text-xs font-medium shrink-0"
              title={t.workflows.sealedHint}
              data-testid="sealed-badge"
            >
              <Lock className="w-3 h-3" />
              {t.workflows.sealed}
            </span>
          )}
        </div>
        {workflow.description && (
          <p className="text-sm text-text-secondary mt-0.5">{workflow.description}</p>
        )}
      </div>
      <div className="flex items-center gap-2">
        {hasFailedRuns && factoryModule && (
          <Button
            variant="secondary"
            size="sm"
            icon={<Wrench className="w-4 h-4" />}
            onClick={onRepair}
            loading={repairLoading}
            disabled={sealed}
            title={sealed ? t.workflows.sealedLocked : undefined}
          >
            {repairLoading ? t.workflows.repairing : t.workflows.repair}
          </Button>
        )}
        <Button
          variant="secondary"
          size="sm"
          icon={<Wand2 className="w-4 h-4" />}
          onClick={onOptimize}
          loading={optimizeLoading}
          disabled={sealed}
          title={sealed ? t.workflows.sealedLocked : undefined}
        >
          {optimizeLoading ? t.workflows.optimizing : t.workflows.optimize}
        </Button>
        <Button
          icon={<Play className="w-4 h-4" />}
          onClick={onExecute}
          loading={executeLoading}
        >
          {t.workflows.execute}
        </Button>
        <Button variant="ghost" size="sm" icon={<Pencil className="w-4 h-4" />} onClick={onEdit} disabled={sealed}
          title={sealed ? t.workflows.sealedLocked : t.common.edit} aria-label={t.common.edit} />
        {sealingModule && (
          <Button
            variant="ghost"
            size="sm"
            icon={sealed ? <LockOpen className="w-4 h-4" /> : <Lock className="w-4 h-4" />}
            onClick={toggleSeal}
            loading={sealWorkflow.isPending}
            title={sealed ? t.workflows.unseal : t.workflows.seal}
            aria-label={sealed ? t.workflows.unseal : t.workflows.seal}
          />
        )}
        <Button
          variant="ghost"
          size="sm"
          icon={<Copy className="w-4 h-4" />}
          onClick={onClone}
          title={t.workflows.clone}
          aria-label={t.workflows.clone}
        />
        <Button
          variant="ghost"
          size="sm"
          icon={<Download className="w-4 h-4" />}
          onClick={() => {
            const safeName = workflow.name.replace(/[^a-zA-Z0-9_-]/g, '_');
            exportWorkflow(workflow.id, `${safeName}.json`);
          }}
          title={t.workflows.exportWorkflow}
          aria-label={t.workflows.exportWorkflow}
        />
        {bpmnModule && (
          <Button
            variant="ghost"
            size="sm"
            icon={<FileCode2 className="w-4 h-4" />}
            onClick={handleBpmnExport}
            title={t.workflows.exportBpmn}
            aria-label={t.workflows.exportBpmn}
          />
        )}
        <Button variant="ghost" size="sm" icon={<Trash2 className="w-4 h-4" />} onClick={onDelete} disabled={sealed}
          title={sealed ? t.workflows.sealedLocked : t.common.delete} aria-label={t.common.delete} />
      </div>
    </div>
  );
}
