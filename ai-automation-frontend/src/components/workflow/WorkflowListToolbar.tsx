import {
  Search,
  ChevronsUpDown,
  CheckSquare,
  X,
  Trash2,
  Bell,
  BellOff,
  AlignLeft,
  AlignJustify,
  Lock,
} from 'lucide-react';
import { Button, Input } from '../ui';
import type { Workflow } from '../../types';
import type { TranslationKeys } from '../../i18n';

interface WorkflowListToolbarProps {
  t: TranslationKeys;
  search: string;
  onSearchChange: (value: string) => void;
  selectMode: boolean;
  onToggleSelectMode: () => void;
  allCollapsed: boolean;
  onToggleAll: () => void;
  selectedIds: Set<string>;
  filtered: Workflow[];
  onRequestDeleteSelected: () => void;
  onBulkToggleAutoTodo: (enable: boolean) => void;
  showDetails: boolean;
  onToggleShowDetails: () => void;
  /** Offered only when at least one workflow is sealed */
  hasSealed?: boolean;
  sealedOnly?: boolean;
  onToggleSealedOnly?: () => void;
}

export function WorkflowListToolbar({
  t,
  search,
  onSearchChange,
  selectMode,
  onToggleSelectMode,
  allCollapsed,
  onToggleAll,
  selectedIds,
  filtered,
  onRequestDeleteSelected,
  onBulkToggleAutoTodo,
  showDetails,
  onToggleShowDetails,
  hasSealed = false,
  sealedOnly = false,
  onToggleSealedOnly,
}: WorkflowListToolbarProps) {
  return (
    <>
      <div className="flex items-center gap-2 mb-3">
        <div className="flex-1">
          <Input
            placeholder={t.common.search}
            value={search}
            onChange={(e) => onSearchChange(e.target.value)}
            icon={<Search className="w-4 h-4 text-text-tertiary" />}
          />
        </div>
        {hasSealed && onToggleSealedOnly && (
          <button
            onClick={onToggleSealedOnly}
            aria-pressed={sealedOnly}
            className={`p-2 rounded-lg transition-colors shrink-0 ${
              sealedOnly
                ? 'bg-accent/10 text-accent'
                : 'hover:bg-surface-raised text-text-tertiary hover:text-text'
            }`}
            title={t.workflows.sealView.filterSealedHint}
            aria-label={t.workflows.sealView.filterSealed}
            data-testid="filter-sealed"
          >
            <Lock className="w-4 h-4" />
          </button>
        )}
        <button
          onClick={onToggleSelectMode}
          className={`p-2 rounded-lg transition-colors shrink-0 ${
            selectMode
              ? 'bg-accent/10 text-accent'
              : 'hover:bg-surface-raised text-text-tertiary hover:text-text'
          }`}
          title={selectMode ? t.workflows.exitSelectMode : t.workflows.selectMode}
        >
          {selectMode ? <X className="w-4 h-4" /> : <CheckSquare className="w-4 h-4" />}
        </button>
        <button
          onClick={onToggleShowDetails}
          className={`p-2 rounded-lg transition-colors shrink-0 ${
            showDetails
              ? 'bg-accent/10 text-accent'
              : 'hover:bg-surface-raised text-text-tertiary hover:text-text'
          }`}
          title={showDetails ? t.workflows.hideDetails : t.workflows.showDetails}
        >
          {showDetails ? <AlignJustify className="w-4 h-4" /> : <AlignLeft className="w-4 h-4" />}
        </button>
        <button
          onClick={onToggleAll}
          className="p-2 rounded-lg hover:bg-surface-raised text-text-tertiary hover:text-text transition-colors shrink-0"
          title={allCollapsed ? t.workflows.expandAll : t.workflows.collapseAll}
        >
          <ChevronsUpDown className="w-4 h-4" />
        </button>
      </div>

      {selectMode && selectedIds.size > 0 && (
        <div className="flex items-center gap-2 mb-2 px-2 py-1.5 rounded-lg bg-accent/5 border border-accent/20">
          <span className="text-xs text-accent font-medium flex-1">
            {selectedIds.size} {t.workflows.selected}
          </span>
          {(() => {
            const selectedWorkflows = filtered.filter((w) => selectedIds.has(w.id));
            const allEnabled = selectedWorkflows.every(
              (w) => !!w.metadata?.autoTodo?.enabled,
            );
            return (
              <Button
                variant="secondary"
                size="sm"
                icon={allEnabled ? <BellOff className="w-3.5 h-3.5" /> : <Bell className="w-3.5 h-3.5" />}
                onClick={() => onBulkToggleAutoTodo(!allEnabled)}
              >
                {allEnabled ? t.workflows.disableAutoTodo : t.workflows.enableAutoTodo}
              </Button>
            );
          })()}
          <Button
            variant="danger"
            size="sm"
            icon={<Trash2 className="w-3.5 h-3.5" />}
            onClick={onRequestDeleteSelected}
          >
            {t.common.delete}
          </Button>
        </div>
      )}
    </>
  );
}
