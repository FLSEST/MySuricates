import { ChevronDown, ChevronRight } from 'lucide-react';
import type { SyncPreviewItem, WorkflowDiff } from '../../types';
import type { useT } from '../../i18n';

type T = ReturnType<typeof useT>;

const ACTION_STYLES: Record<string, string> = {
  NEW: 'bg-success/15 text-success',
  UPDATE: 'bg-warning/15 text-warning',
  SKIP: 'bg-surface-raised text-text-tertiary',
};

function actionLabel(action: string, t: T) {
  if (action === 'NEW') return t.settings.syncActionNew;
  if (action === 'UPDATE') return t.settings.syncActionUpdate;
  return t.settings.syncActionSkip;
}

/** Collapsible group of the sync dialog, e.g. all workflows, with "selected/total" on the right. */
export function Section({ label, count, total, collapsed, onToggle, children }: {
  label: string;
  count: string;
  total: number;
  collapsed: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}) {
  return (
    <div>
      <button
        onClick={onToggle}
        className="flex items-center justify-between w-full px-1 py-1.5 text-sm font-medium text-text hover:bg-surface-raised rounded transition-colors"
      >
        <div className="flex items-center gap-1.5">
          {collapsed
            ? <ChevronRight className="w-4 h-4 text-text-tertiary" />
            : <ChevronDown className="w-4 h-4 text-text-tertiary" />}
          <span>{label} ({total})</span>
        </div>
        <span className="text-xs text-text-tertiary font-normal">{count}</span>
      </button>
      {!collapsed && <div className="ml-2">{children}</div>}
    </div>
  );
}

function blocksDiffLabel(diff: WorkflowDiff, t: T): string {
  if (diff.blocksAdded > 0) return t.settings.syncDiffBlocksAdded.replace('{n}', String(diff.blocksAdded));
  if (diff.blocksRemoved > 0) return t.settings.syncDiffBlocksRemoved.replace('{n}', String(diff.blocksRemoved));
  return t.settings.syncDiffBlocksChanged;
}

function DiffDetails({ diff, t }: { diff: WorkflowDiff; t: T }) {
  const parts: string[] = [];
  if (diff.changedFields.includes('blocks')) parts.push(blocksDiffLabel(diff, t));
  if (diff.changedFields.includes('model') && diff.modelBefore != null)
    parts.push(`model: ${diff.modelBefore} → ${diff.modelAfter}`);
  if (diff.changedFields.includes('instructions')) parts.push(t.settings.syncDiffInstructions);
  if (diff.changedFields.includes('description')) parts.push(t.settings.syncDiffDescription);
  if (parts.length === 0) return null;
  return (
    <div className="ml-6 mt-0.5 text-[10px] text-text-tertiary leading-tight">
      {parts.join(' · ')}
    </div>
  );
}

/** One definition with its checkbox, what the sync would do (new/update/skip) and, for an update, what changes. */
export function ItemRow({ item, checked, onToggle, indent, t }: {
  item: SyncPreviewItem;
  checked: boolean;
  onToggle: () => void;
  indent?: boolean;
  t: T;
}) {
  return (
    <label
      className={`block py-1 px-1 rounded hover:bg-surface-raised cursor-pointer transition-colors ${indent ? 'ml-4' : ''}`}
    >
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <input
            type="checkbox"
            checked={checked}
            onChange={onToggle}
            className="rounded border-border text-accent focus:ring-accent shrink-0"
          />
          <span className="text-sm text-text truncate">{item.name}</span>
        </div>
        <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded shrink-0 ${ACTION_STYLES[item.action] ?? ''}`}>
          {actionLabel(item.action, t)}
        </span>
      </div>
      {item.action === 'UPDATE' && item.diff && item.diff.changedFields.length > 0 && (
        <DiffDetails diff={item.diff} t={t} />
      )}
    </label>
  );
}
