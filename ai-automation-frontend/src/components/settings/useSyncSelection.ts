import { useCallback, useMemo, useState } from 'react';
import type { SyncDefinitionsRequest, SyncPreview, SyncPreviewItem } from '../../types';

/** The kinds of definitions a sync preview lists, in the order the dialog shows them. */
export type SyncKind = 'project' | 'workflow' | 'skill' | 'chain';
const KINDS: SyncKind[] = ['project', 'workflow', 'skill', 'chain'];

/** The rows of one kind; previews without chains have none. */
export function syncItems(preview: SyncPreview, kind: SyncKind): SyncPreviewItem[] {
  if (kind === 'project') return preview.projects;
  if (kind === 'workflow') return preview.workflows;
  if (kind === 'skill') return preview.skills;
  return preview.chains ?? [];
}

const keyOf = (kind: SyncKind, id: string) => `${kind}:${id}`;

/**
 * Selection in the definition-sync dialog. As soon as the preview is there, every row that changes
 * something (NEW or UPDATE) is selected — set while rendering, not in an effect. Sections can be
 * collapsed; `reset` (when the dialog closes) clears both, so the next preview selects anew.
 */
export function useSyncSelection(preview: SyncPreview | undefined) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [collapsed, setCollapsed] = useState<Partial<Record<SyncKind, boolean>>>({});
  const [initialized, setInitialized] = useState(false);

  if (preview && !initialized) {
    setSelected(new Set(KINDS.flatMap((kind) => syncItems(preview, kind)
      .filter((item) => item.action !== 'SKIP')
      .map((item) => keyOf(kind, item.id)))));
    setInitialized(true);
  }

  const allItems = useMemo(
    () => (preview ? KINDS.flatMap((kind) => syncItems(preview, kind).map((item) => keyOf(kind, item.id))) : []),
    [preview],
  );
  const allSelected = allItems.length > 0 && allItems.every((k) => selected.has(k));

  const isSelected = (kind: SyncKind, id: string) => selected.has(keyOf(kind, id));

  const toggle = (kind: SyncKind, id: string) => {
    const key = keyOf(kind, id);
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const toggleAll = () => setSelected(allSelected ? new Set() : new Set(allItems));

  const toggleSection = (kind: SyncKind) => setCollapsed((prev) => ({ ...prev, [kind]: !prev[kind] }));

  /** "selected/total" for the section header. */
  const sectionCount = (kind: SyncKind) => {
    const items = preview ? syncItems(preview, kind) : [];
    return `${items.filter((item) => selected.has(keyOf(kind, item.id))).length}/${items.length}`;
  };

  const reset = useCallback(() => {
    setInitialized(false);
    setSelected(new Set());
    setCollapsed({});
  }, []);

  /** The selected ids per kind; a kind without any stays out of the request. */
  const buildRequest = (): SyncDefinitionsRequest => {
    const ids = (kind: SyncKind) => {
      const list = (preview ? syncItems(preview, kind) : [])
        .filter((item) => selected.has(keyOf(kind, item.id)))
        .map((item) => item.id);
      return list.length ? list : undefined;
    };
    return { projectIds: ids('project'), workflowIds: ids('workflow'), skillIds: ids('skill'), chainIds: ids('chain') };
  };

  return {
    selectedCount: selected.size,
    allSelected,
    isSelected,
    toggle,
    toggleAll,
    isCollapsed: (kind: SyncKind) => !!collapsed[kind],
    toggleSection,
    sectionCount,
    reset,
    buildRequest,
  };
}
