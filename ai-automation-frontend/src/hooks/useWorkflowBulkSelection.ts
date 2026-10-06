import { useState, useCallback } from 'react';
import { useDeleteWorkflow, useUpdateWorkflow } from './useWorkflows';
import type { Workflow } from '../types';

export interface WorkflowDeleteConfirm {
  open: boolean;
  ids: string[];
  names: string[];
}

const CLOSED: WorkflowDeleteConfirm = { open: false, ids: [], names: [] };

/**
 * Selection mode of the workflow list and deletion: mark single workflows or whole projects,
 * toggle auto-TODO for the marked ones, delete (one or the marked ones) only
 * after confirmation and one after another. While deleting, the confirmation cannot be
 * closed. `onDeleted` receives the deleted IDs so the page can drop a selection below them.
 */
export function useWorkflowBulkSelection(
  filtered: Workflow[],
  onDeleted: (ids: string[]) => void,
) {
  const deleteWorkflow = useDeleteWorkflow();
  const updateWorkflow = useUpdateWorkflow();
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [deleteConfirm, setDeleteConfirm] = useState<WorkflowDeleteConfirm>(CLOSED);
  const [deleting, setDeleting] = useState(false);

  const toggleSelectMode = useCallback(() => {
    setSelectMode((prev) => {
      if (prev) setSelectedIds(new Set());
      return !prev;
    });
  }, []);

  const toggleWorkflowSelect = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const toggleProjectSelect = useCallback((projectWorkflows: Workflow[]) => {
    setSelectedIds((prev) => {
      const ids = projectWorkflows.map((w) => w.id);
      const allSelected = ids.every((id) => prev.has(id));
      const next = new Set(prev);
      if (allSelected) {
        ids.forEach((id) => next.delete(id));
      } else {
        ids.forEach((id) => next.add(id));
      }
      return next;
    });
  }, []);

  const requestDeleteSingle = useCallback((wf: Workflow) => {
    setDeleteConfirm({ open: true, ids: [wf.id], names: [wf.name] });
  }, []);

  const requestDeleteSelected = useCallback(() => {
    if (selectedIds.size === 0) return;
    const names = filtered.filter((w) => selectedIds.has(w.id)).map((w) => w.name);
    setDeleteConfirm({ open: true, ids: [...selectedIds], names });
  }, [selectedIds, filtered]);

  const confirmDelete = useCallback(async () => {
    setDeleting(true);
    try {
      for (const id of deleteConfirm.ids) {
        await deleteWorkflow.mutateAsync(id);
      }
      setSelectedIds((prev) => {
        const next = new Set(prev);
        deleteConfirm.ids.forEach((id) => next.delete(id));
        return next;
      });
      onDeleted(deleteConfirm.ids);
    } finally {
      setDeleting(false);
      setDeleteConfirm(CLOSED);
    }
  }, [deleteConfirm, deleteWorkflow, onDeleted]);

  // Closing is blocked while the deletes run (Escape, backdrop and cancel alike)
  const cancelDelete = () => !deleting && setDeleteConfirm(CLOSED);

  const bulkToggleAutoTodo = (enable: boolean) => {
    for (const id of selectedIds) {
      updateWorkflow.mutate({
        id,
        data: { metadata: { autoTodo: { enabled: enable } } },
      });
    }
  };

  return {
    selectMode, toggleSelectMode, selectedIds, toggleWorkflowSelect, toggleProjectSelect,
    deleteConfirm, deleting, requestDeleteSingle, requestDeleteSelected, confirmDelete, cancelDelete,
    bulkToggleAutoTodo,
  };
}
