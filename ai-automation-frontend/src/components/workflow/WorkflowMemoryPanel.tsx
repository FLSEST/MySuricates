import { useState } from 'react';
import { Brain, Sparkles } from 'lucide-react';
import { Card, CardHeader, Button, Modal, ConfirmModal } from '../ui';
import { MonacoEditor } from '../shared/MonacoEditor';
import {
  useWorkflowMemories, useActivateMemory, useUpdateMemory, useDeleteMemory,
} from '../../hooks/useWorkflowMemory';
import { useMemoryExtraction } from '../../hooks/useMemoryExtraction';
import { useT } from '../../i18n';
import { MemoryExtractModal } from './MemoryExtractModal';
import { WorkflowMemoryItem } from './WorkflowMemoryItem';
import type { WorkflowRun, WorkflowMemory } from '../../types';

interface Props {
  workflowId: string;
  /** List rows are enough — the panel reads no steps. */
  runs?: Omit<WorkflowRun, 'steps'>[];
}

export function WorkflowMemoryPanel({ workflowId, runs }: Props) {
  const t = useT();
  const { data: memories } = useWorkflowMemories(workflowId);
  const extraction = useMemoryExtraction(workflowId);
  const activateMemory = useActivateMemory(workflowId);
  const updateMemory = useUpdateMemory(workflowId);
  const deleteMemory = useDeleteMemory(workflowId);

  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [showEdit, setShowEdit] = useState(false);
  const [editData, setEditData] = useState<{ id: string; title: string; content: string }>({
    id: '', title: '', content: '',
  });
  const [showDelete, setShowDelete] = useState<string | null>(null);

  const openEdit = (mem: WorkflowMemory) => {
    setEditData({ id: mem.id, title: mem.title, content: mem.content });
    setShowEdit(true);
  };

  const handleSaveEdit = () => {
    updateMemory.mutate(
      { memoryId: editData.id, title: editData.title, content: editData.content },
      { onSuccess: () => setShowEdit(false) }
    );
  };

  return (
    <Card>
      <CardHeader
        title={t.memory.title}
        action={
          <Button
            size="sm"
            variant="secondary"
            icon={<Sparkles className="w-3.5 h-3.5" />}
            onClick={extraction.show}
            disabled={!runs?.length}
          >
            {t.memory.extractKnowledge}
          </Button>
        }
      />

      {!memories?.length ? (
        <p className="text-sm text-text-tertiary flex items-center gap-2">
          <Brain className="w-4 h-4" />
          {t.memory.noMemories}
        </p>
      ) : (
        <div className="space-y-3">
          {memories.map((mem, idx) => (
            <WorkflowMemoryItem
              key={mem.id}
              memory={mem}
              fallbackTitle={`v${memories.length - idx}`}
              runs={runs}
              expanded={expandedId === mem.id}
              onToggleExpanded={() => setExpandedId(expandedId === mem.id ? null : mem.id)}
              onActivate={() => activateMemory.mutate(mem.id)}
              activating={activateMemory.isPending}
              onEdit={() => openEdit(mem)}
              onDelete={() => setShowDelete(mem.id)}
            />
          ))}
        </div>
      )}

      <MemoryExtractModal extraction={extraction} runs={runs} />

      {/* Edit Modal */}
      <Modal
        open={showEdit}
        onClose={() => setShowEdit(false)}
        title={t.memory.editMemory}
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowEdit(false)}>{t.common.cancel}</Button>
            <Button onClick={handleSaveEdit} loading={updateMemory.isPending}>{t.common.save}</Button>
          </>
        }
      >
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-text mb-1.5">{t.common.name}</label>
            <input
              type="text"
              value={editData.title}
              onChange={(e) => setEditData({ ...editData, title: e.target.value })}
              className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm focus:outline-hidden focus:ring-2 focus:ring-accent/50"
            />
          </div>
          <MonacoEditor
            value={editData.content}
            onChange={(val) => setEditData({ ...editData, content: val })}
            language="markdown"
            height="300px"
            prose
          />
        </div>
      </Modal>

      {/* Delete confirmation */}
      <ConfirmModal
        open={!!showDelete}
        onClose={() => setShowDelete(null)}
        onConfirm={() => {
          if (showDelete) {
            deleteMemory.mutate(showDelete, { onSuccess: () => setShowDelete(null) });
          }
        }}
        title={t.common.delete}
        message={t.common.deleteConfirm}
        confirmLabel={t.common.delete}
        variant="danger"
        isPending={deleteMemory.isPending}
      />
    </Card>
  );
}
