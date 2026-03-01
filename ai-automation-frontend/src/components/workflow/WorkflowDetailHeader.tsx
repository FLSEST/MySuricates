import {
  ArrowLeft, Play, Pencil, Wand2, Copy, Download, Trash2,
} from 'lucide-react';
import { Button } from '../ui';
import type { Workflow } from '../../types';
import { exportWorkflow } from '../../hooks/useWorkflows';

interface WorkflowDetailHeaderProps {
  workflow: Workflow;
  t: ReturnType<typeof import('../../i18n').useT>;
  onNavigateBack: () => void;
  onEdit: () => void;
  onExecute: () => void;
  onOptimize: () => void;
  onClone: () => void;
  onDelete: () => void;
  executeLoading: boolean;
  optimizeLoading: boolean;
}

export function WorkflowDetailHeader({
  workflow,
  t,
  onNavigateBack,
  onEdit,
  onExecute,
  onOptimize,
  onClone,
  onDelete,
  executeLoading,
  optimizeLoading,
}: WorkflowDetailHeaderProps) {
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
        </div>
        {workflow.description && (
          <p className="text-sm text-text-secondary mt-0.5">{workflow.description}</p>
        )}
      </div>
      <div className="flex items-center gap-2">
        <Button
          variant="secondary"
          size="sm"
          icon={<Wand2 className="w-4 h-4" />}
          onClick={onOptimize}
          loading={optimizeLoading}
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
        <Button variant="ghost" size="sm" icon={<Pencil className="w-4 h-4" />} onClick={onEdit} aria-label={t.common.edit} />
        <Button
          variant="ghost"
          size="sm"
          icon={<Copy className="w-4 h-4" />}
          onClick={onClone}
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
          aria-label={t.workflows.exportWorkflow}
        />
        <Button variant="ghost" size="sm" icon={<Trash2 className="w-4 h-4" />} onClick={onDelete} aria-label={t.common.delete} />
      </div>
    </div>
  );
}
