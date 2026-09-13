import { useState, useEffect } from 'react';
import { Brain, ChevronDown, ChevronUp, Check, Pencil, Trash2, Sparkles, Loader2 } from 'lucide-react';
import { Card, CardHeader, Button, Modal, Select } from '../ui';
import { MonacoEditor } from '../shared/MonacoEditor';
import {
  useWorkflowMemories, useExtractMemory, useActivateMemory,
  useUpdateMemory, useDeleteMemory,
} from '../../hooks/useWorkflowMemory';
import { useT } from '../../i18n';
import type { WorkflowRun, WorkflowMemory } from '../../types';

interface Props {
  workflowId: string;
  runs?: WorkflowRun[];
}

export function WorkflowMemoryPanel({ workflowId, runs }: Props) {
  const t = useT();
  const { data: memories } = useWorkflowMemories(workflowId);
  const extractMemory = useExtractMemory(workflowId);
  const activateMemory = useActivateMemory(workflowId);
  const updateMemory = useUpdateMemory(workflowId);
  const deleteMemory = useDeleteMemory(workflowId);

  const [showExtract, setShowExtract] = useState(false);
  const [selectedRunIds, setSelectedRunIds] = useState<string[]>([]);
  const [provider, setProvider] = useState('CLAUDE');
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [showEdit, setShowEdit] = useState(false);
  const [editData, setEditData] = useState<{ id: string; title: string; content: string }>({
    id: '', title: '', content: '',
  });
  const [showDelete, setShowDelete] = useState<string | null>(null);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);

  useEffect(() => {
    if (!extractMemory.isPending) return;
    const interval = setInterval(() => setElapsedSeconds((s) => s + 1), 1000);
    return () => clearInterval(interval);
  }, [extractMemory.isPending]);

  const handleExtract = () => {
    setElapsedSeconds(0);
    extractMemory.mutate(
      { runIds: selectedRunIds.length > 0 ? selectedRunIds : undefined, provider },
      {
        onSuccess: () => {
          setShowExtract(false);
          setSelectedRunIds([]);
        },
      }
    );
  };

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
            onClick={() => setShowExtract(true)}
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
            <div
              key={mem.id}
              className={`rounded-lg border p-3 transition-colors ${
                mem.active
                  ? 'border-accent/40 bg-accent/5'
                  : 'border-border bg-surface'
              }`}
            >
              {/* Header */}
              <div className="flex items-start justify-between gap-2">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-text truncate">
                      {mem.title || `v${memories.length - idx}`}
                    </span>
                    {mem.active && (
                      <span className="shrink-0 inline-flex items-center gap-1 px-1.5 py-0.5 rounded-sm text-[10px] font-medium bg-accent/10 text-accent">
                        <Check className="w-3 h-3" />
                        {t.memory.active}
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-3 mt-1 text-xs text-text-tertiary flex-wrap">
                    <span>{new Date(mem.createdAt).toLocaleString()}</span>
                    <span>{mem.provider}</span>
                    {mem.sourceRunIds.length > 0 && (() => {
                      const sourceRuns = runs?.filter(r => mem.sourceRunIds.includes(r.id)) ?? [];
                      return (
                        <span className="flex items-center gap-1 flex-wrap">
                          <span>{t.memory.sourceRuns}:</span>
                          {sourceRuns.length > 0
                            ? sourceRuns.map(r => (
                                <span key={r.id} className="px-1.5 py-0.5 rounded-sm bg-surface-raised text-[10px]">
                                  {new Date(r.startedAt).toLocaleString()}
                                </span>
                              ))
                            : <span>{mem.sourceRunIds.length} Runs</span>
                          }
                        </span>
                      );
                    })()}
                  </div>
                  {mem.tags.length > 0 && (
                    <div className="flex flex-wrap gap-1 mt-1.5">
                      {mem.tags.map((tag) => (
                        <span
                          key={tag}
                          className="px-1.5 py-0.5 rounded-sm text-[10px] font-medium bg-surface-raised text-text-secondary"
                        >
                          {tag}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
                <button
                  onClick={() => setExpandedId(expandedId === mem.id ? null : mem.id)}
                  className="p-1 rounded-sm text-text-tertiary hover:text-text transition-colors"
                >
                  {expandedId === mem.id ? (
                    <ChevronUp className="w-4 h-4" />
                  ) : (
                    <ChevronDown className="w-4 h-4" />
                  )}
                </button>
              </div>

              {/* Expanded preview */}
              {expandedId === mem.id && (
                <div className="mt-3 pt-3 border-t border-border">
                  <pre className="text-xs text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded-lg p-3 max-h-60 overflow-y-auto">
                    {mem.content}
                  </pre>
                </div>
              )}

              {/* Actions */}
              <div className="flex items-center gap-2 mt-2">
                {!mem.active && (
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => activateMemory.mutate(mem.id)}
                    loading={activateMemory.isPending}
                  >
                    {t.memory.activate}
                  </Button>
                )}
                <Button
                  size="sm"
                  variant="ghost"
                  icon={<Pencil className="w-3 h-3" />}
                  onClick={() => openEdit(mem)}
                />
                <Button
                  size="sm"
                  variant="ghost"
                  icon={<Trash2 className="w-3 h-3" />}
                  onClick={() => setShowDelete(mem.id)}
                />
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Extract Modal */}
      <Modal
        open={showExtract}
        onClose={extractMemory.isPending ? () => {} : () => setShowExtract(false)}
        title={t.memory.extractKnowledge}
        size="sm"
        footer={
          extractMemory.isPending ? undefined : (
            <>
              <Button variant="secondary" onClick={() => setShowExtract(false)}>{t.common.cancel}</Button>
              <Button onClick={handleExtract}>
                {t.memory.extractKnowledge}
              </Button>
            </>
          )
        }
      >
        {extractMemory.isPending ? (
          <div className="flex flex-col items-center gap-3 py-8 text-center">
            <Loader2 className="w-8 h-8 text-accent animate-spin" />
            <p className="text-sm font-medium text-text">{t.memory.extracting}</p>
            <p className="text-xs text-text-tertiary">{elapsedSeconds}s — {t.memory.extractingHint}</p>
          </div>
        ) : (
          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-text mb-1.5">{t.memory.selectRuns}</label>
              <p className="text-xs text-text-tertiary mb-2">{t.memory.selectRunsHint}</p>
              <div className="max-h-40 overflow-y-auto space-y-1 border border-border rounded-lg p-2">
                {runs?.slice(0, 20).map((run) => (
                  <label key={run.id} className="flex items-center gap-2 text-sm text-text py-1 px-1 rounded-sm hover:bg-surface-raised cursor-pointer">
                    <input
                      type="checkbox"
                      checked={selectedRunIds.includes(run.id)}
                      onChange={(e) => {
                        if (e.target.checked) {
                          setSelectedRunIds([...selectedRunIds, run.id]);
                        } else {
                          setSelectedRunIds(selectedRunIds.filter((id) => id !== run.id));
                        }
                      }}
                      className="rounded-sm border-border"
                    />
                    <span className={`w-2 h-2 rounded-full ${
                      run.status === 'COMPLETED' ? 'bg-success' :
                      run.status === 'FAILED' ? 'bg-danger' : 'bg-warning'
                    }`} />
                    <span className="truncate">{new Date(run.startedAt).toLocaleString()}</span>
                    <span className="text-xs text-text-tertiary ml-auto">{run.status}</span>
                  </label>
                ))}
              </div>
            </div>
            <Select
              label={t.memory.selectProvider}
              value={provider}
              onChange={(e) => setProvider(e.target.value)}
              options={[
                { value: 'CLAUDE', label: 'Claude' },
                { value: 'CODEX', label: 'Codex (OpenAI)' },
                { value: 'PI', label: 'pi (Mario Zechner)' },
              ]}
            />
          </div>
        )}
      </Modal>

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
          />
        </div>
      </Modal>

      {/* Delete confirmation */}
      <Modal
        open={!!showDelete}
        onClose={() => setShowDelete(null)}
        title={t.common.delete}
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowDelete(null)}>{t.common.cancel}</Button>
            <Button
              variant="danger"
              onClick={() => {
                if (showDelete) {
                  deleteMemory.mutate(showDelete, { onSuccess: () => setShowDelete(null) });
                }
              }}
              loading={deleteMemory.isPending}
            >
              {t.common.delete}
            </Button>
          </>
        }
      >
        <p className="text-sm text-text-secondary">{t.common.deleteConfirm}</p>
      </Modal>
    </Card>
  );
}
