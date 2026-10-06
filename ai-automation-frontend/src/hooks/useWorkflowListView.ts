import { useMemo, useState, useCallback, useEffect } from 'react';
import { useT } from '../i18n';
import type { SealFilter } from '../components/workflow/SealFilterTabs';
import type { Workflow } from '../types';

// ── Persistence helpers ──────────────────────────────────────

const STORAGE_KEY = 'workflows-collapsed-projects';
const SELECTED_KEY = 'workflows-selected-id';
const SHOW_DETAILS_KEY = 'workflows-show-row-details';
const VIEW_MODE_KEY = 'workflows-view-mode';

export type WorkflowViewMode = 'classic' | 'discovery';

export interface WorkflowProjectGroup {
  projectId: string;
  projectName: string;
  workflows: Workflow[];
}

function loadCollapsed(): Set<string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return new Set(JSON.parse(raw));
  } catch { /* ignore */ }
  return new Set(['__all__']);
}

function saveCollapsed(set: Set<string>) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...set]));
  } catch { /* ignore */ }
}

function loadSelectedId(): string | null {
  try {
    return localStorage.getItem(SELECTED_KEY);
  } catch { return null; }
}

function saveSelectedId(id: string | null) {
  try {
    if (id) localStorage.setItem(SELECTED_KEY, id);
    else localStorage.removeItem(SELECTED_KEY);
  } catch { /* ignore */ }
}

/**
 * View of the workflow list: search and seal filter, grouping by project (sorted by name),
 * collapsed projects, the selected workflow (falls back to the first hit),
 * row details and view mode. Collapsed projects, selection, row details and mode
 * survive a page change in localStorage.
 */
export function useWorkflowListView(workflows: Workflow[] | undefined) {
  const t = useT();
  const [search, setSearch] = useState('');
  const [selectedWorkflowId, setSelectedWorkflowId] = useState<string | null>(loadSelectedId);
  const [collapsedProjects, setCollapsedProjects] = useState<Set<string>>(loadCollapsed);
  const [showDetails, setShowDetails] = useState<boolean>(() => {
    try { return localStorage.getItem(SHOW_DETAILS_KEY) === '1'; } catch { return false; }
  });
  const [viewMode, setViewMode] = useState<WorkflowViewMode>(() => {
    try {
      const v = localStorage.getItem(VIEW_MODE_KEY);
      return v === 'discovery' ? 'discovery' : 'classic';
    } catch { return 'classic'; }
  });

  useEffect(() => { saveCollapsed(collapsedProjects); }, [collapsedProjects]);
  useEffect(() => { saveSelectedId(selectedWorkflowId); }, [selectedWorkflowId]);
  useEffect(() => {
    try { localStorage.setItem(SHOW_DETAILS_KEY, showDetails ? '1' : '0'); } catch { /* ignore */ }
  }, [showDetails]);
  useEffect(() => {
    try { localStorage.setItem(VIEW_MODE_KEY, viewMode); } catch { /* ignore */ }
  }, [viewMode]);

  const [sealFilter, setSealFilter] = useState<SealFilter>('all');
  const sealedCount = useMemo(() => (workflows ?? []).filter((w) => w.sealed).length, [workflows]);
  const filtered = useMemo(
    () => {
      const all = workflows ?? [];
      const base = sealFilter === 'all' ? all : all.filter((w) => !!w.sealed === (sealFilter === 'sealed'));
      if (!search) return base;
      const q = search.toLowerCase();
      return (
        base.filter(
          (w) =>
            w.name.toLowerCase().includes(q) ||
            w.projectName.toLowerCase().includes(q) ||
            (w.description?.toLowerCase().includes(q) ?? false),
        )
      );
    },
    [workflows, search, sealFilter],
  );

  const groupedByProject = useMemo(() => {
    const groups = new Map<string, WorkflowProjectGroup>();
    for (const wf of filtered) {
      const key = wf.projectId;
      if (!groups.has(key)) {
        groups.set(key, { projectId: key, projectName: wf.projectName || t.common.project, workflows: [] });
      }
      groups.get(key)!.workflows.push(wf);
    }
    return Array.from(groups.values()).sort((a, b) =>
      a.projectName.localeCompare(b.projectName),
    );
  }, [filtered, t.common.project]);

  // Without a stored choice ('__all__') the projects of the first load start collapsed. Resolved
  // against the unfiltered list, so an active search does not decide it; projects that appear later
  // (created, imported, installed) are not in the set and show open.
  if (collapsedProjects.has('__all__') && workflows?.length) {
    setCollapsedProjects(new Set(workflows.map((w) => w.projectId)));
  }

  const selectedWorkflow = useMemo(() => {
    if (!filtered.length) return null;
    const found = selectedWorkflowId ? filtered.find((w) => w.id === selectedWorkflowId) : null;
    return found ?? filtered[0];
  }, [filtered, selectedWorkflowId]);

  const isProjectCollapsed = useCallback(
    (projectId: string) => collapsedProjects.has(projectId),
    [collapsedProjects],
  );

  const toggleProject = useCallback((projectId: string) => {
    setCollapsedProjects((prev) => {
      const next = new Set(prev);
      if (next.has(projectId)) next.delete(projectId);
      else next.add(projectId);
      return next;
    });
  }, []);

  const allCollapsed = groupedByProject.length > 0 && groupedByProject.every((g) => collapsedProjects.has(g.projectId));

  const toggleAll = useCallback(() => {
    setCollapsedProjects((prev) => {
      const allIds = groupedByProject.map((g) => g.projectId);
      const allAreCollapsed = allIds.every((id) => prev.has(id));
      return allAreCollapsed ? new Set() : new Set(allIds);
    });
  }, [groupedByProject]);

  /** Deleted workflows: a remembered choice among them is dropped (the first match shows instead). */
  const forgetSelection = useCallback((deletedIds: string[]) => {
    setSelectedWorkflowId((prev) => (deletedIds.includes(prev ?? '') ? null : prev));
  }, []);

  const toggleShowDetails = useCallback(() => setShowDetails((v) => !v), []);

  return {
    search, setSearch, sealFilter, setSealFilter, sealedCount,
    filtered, groupedByProject,
    selectedWorkflow, setSelectedWorkflowId, forgetSelection,
    isProjectCollapsed, toggleProject, allCollapsed, toggleAll,
    showDetails, toggleShowDetails, viewMode, setViewMode,
  };
}
