import {
  ChevronRight,
  ChevronDown,
  FolderOpen,
  CheckSquare,
  Square,
} from 'lucide-react';
import { WorkflowRow } from './WorkflowRow';
import type { useNavigate } from 'react-router-dom';
import type { Workflow } from '../../types';

interface ProjectGroupProps {
  projectId: string;
  projectName: string;
  workflows: Workflow[];
  collapsed: boolean;
  onToggle: () => void;
  selectedId: string | null;
  onSelect: (id: string) => void;
  onExecute: (w: Workflow) => void;
  onDelete: (w: Workflow) => void;
  navigate: ReturnType<typeof useNavigate>;
  selectMode: boolean;
  selectedIds: Set<string>;
  onToggleSelect: (id: string) => void;
  onToggleProjectSelect: (workflows: Workflow[]) => void;
  showDetails?: boolean;
}

export function ProjectGroup({
  projectName,
  workflows,
  collapsed,
  onToggle,
  selectedId,
  onSelect,
  onExecute,
  onDelete,
  navigate,
  selectMode,
  selectedIds,
  onToggleSelect,
  onToggleProjectSelect,
  showDetails = false,
}: ProjectGroupProps) {
  const allSelected = workflows.every((w) => selectedIds.has(w.id));
  const someSelected = !allSelected && workflows.some((w) => selectedIds.has(w.id));

  return (
    <div>
      <div className="flex items-center gap-1">
        {selectMode && !collapsed && (
          <button
            onClick={(e) => { e.stopPropagation(); onToggleProjectSelect(workflows); }}
            className="p-0.5 shrink-0 text-text-tertiary hover:text-accent transition-colors"
            title={allSelected ? 'Alle abwählen' : 'Alle auswählen'}
          >
            {allSelected ? (
              <CheckSquare className="w-3.5 h-3.5 text-accent" />
            ) : someSelected ? (
              <CheckSquare className="w-3.5 h-3.5 text-accent/50" />
            ) : (
              <Square className="w-3.5 h-3.5" />
            )}
          </button>
        )}
        <button
          onClick={onToggle}
          className="flex items-center gap-1.5 flex-1 px-2 py-1.5 text-left rounded-md hover:bg-surface-raised transition-colors group"
        >
          {collapsed ? (
            <ChevronRight className="w-3.5 h-3.5 text-text-tertiary" />
          ) : (
            <ChevronDown className="w-3.5 h-3.5 text-text-tertiary" />
          )}
          <FolderOpen className="w-3.5 h-3.5 text-text-tertiary" />
          <span className="text-sm font-medium text-text-secondary truncate">{projectName}</span>
          {workflows[0]?.projectSubtitle && (
            <span className="text-xs text-text-tertiary truncate">{workflows[0].projectSubtitle}</span>
          )}
          <span className="text-xs text-text-tertiary ml-auto shrink-0">({workflows.length})</span>
        </button>
      </div>
      {!collapsed && workflows[0]?.projectDescription && (
        <p className="ml-8 mt-0.5 mb-1 text-xs text-text-tertiary italic line-clamp-2">
          {workflows[0].projectDescription}
        </p>
      )}
      {!collapsed && (
        <div className="ml-3 mt-0.5 space-y-0.5">
          {workflows.map((wf) => (
            <WorkflowRow
              key={wf.id}
              workflow={wf}
              selected={wf.id === selectedId}
              onSelect={() => onSelect(wf.id)}
              onExecute={() => onExecute(wf)}
              onDelete={() => onDelete(wf)}
              navigate={navigate}
              selectMode={selectMode}
              isChecked={selectedIds.has(wf.id)}
              onToggleCheck={() => onToggleSelect(wf.id)}
              showDetails={showDetails}
            />
          ))}
        </div>
      )}
    </div>
  );
}
