import { Layers, GitBranch } from 'lucide-react';
import { Badge } from '../ui';
import type { DialogProjectGroup } from '../../hooks/useDialogImport';

/** Panel 1 of the session takeover: projects (and „Andere Sessions“) with their session count. */
export function ProjectSidebar({
  groups, selectedId, onSelect,
}: {
  groups: DialogProjectGroup[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  return (
    <div className="flex flex-col gap-1">
      {groups.map(g => {
        const active = selectedId === g.id;
        return (
          <button
            key={g.id}
            onClick={() => onSelect(g.id)}
            className={`w-full text-left flex items-center justify-between px-3 py-2.5 rounded-lg transition-colors ${
              active
                ? 'bg-accent/10 text-accent ring-1 ring-accent/30'
                : 'text-text hover:bg-surface-hover'
            }`}
          >
            <div className="flex items-center gap-2 min-w-0">
              {g.isOther
                ? <Layers className="w-4 h-4 shrink-0 text-text-secondary" />
                : <GitBranch className="w-4 h-4 shrink-0 text-text-secondary" />}
              <span className="text-sm font-medium truncate">{g.label}</span>
            </div>
            <Badge variant="default">{g.count}</Badge>
          </button>
        );
      })}
    </div>
  );
}
