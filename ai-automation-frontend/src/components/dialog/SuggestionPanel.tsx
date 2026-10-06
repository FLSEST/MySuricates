import { useState, useMemo } from 'react';
import { CheckCircle2, AlertCircle, XCircle, ChevronRight, Pencil } from 'lucide-react';
import { Button, Input, Select, Spinner, Badge, Modal, ModalFooter } from '../ui';
import { MonacoEditor } from '../shared/MonacoEditor';
import { BuildFindingsNote } from '../shared/BuildFindingsNote';
import { useT } from '../../i18n';
import { useToast } from '../../hooks/useToast';
import type { DialogWorkflowSuggestion, BlockDefinition } from '../../types';
import type { BadgeVariant } from '../ui/Badge';

const blockTypeVariant: Record<string, BadgeVariant> = {
  simple: 'success', agentic: 'success', 'skill-capability': 'success',
  shell: 'accent', 'sub-workflow': 'accent',
  approval: 'warning', 'human-input': 'warning',
  retry: 'default', foreach: 'default', while: 'default', conditional: 'default',
  compress: 'accent', 'compress-ai': 'success',
};

function ConfidenceBadge({ confidence }: { confidence: number }) {
  const pct = Math.round(confidence * 100);
  if (confidence >= 0.7) return <Badge variant="success">{pct}%</Badge>;
  if (confidence >= 0.5) return <Badge variant="warning">{pct}%</Badge>;
  return <Badge variant="danger">{pct}%</Badge>;
}

/**
 * Panel 3 of the session takeover: the workflow suggestion from a session — safety, patterns,
 * build errors, blocks (configuration per block editable in the JSON editor) and creating it with name
 * and project.
 */
export function SuggestionPanel({
  suggestion, projects, onCreateWorkflow, isCreating,
}: {
  suggestion: DialogWorkflowSuggestion;
  projects: { id: string; name: string }[];
  onCreateWorkflow: (projectId: string, name: string, blocks: BlockDefinition[]) => void;
  isCreating: boolean;
}) {
  const t = useT();
  const toast = useToast();
  const [projectId, setProjectId] = useState('');
  const [name, setName] = useState(suggestion.suggestedName ?? '');
  const [editedBlocks, setEditedBlocks] = useState<BlockDefinition[]>(suggestion.blocks);
  const [editingBlock, setEditingBlock] = useState<{ index: number; config: string } | null>(null);

  const projectOptions = useMemo(() => [
    { value: '', label: t.common.selectProject },
    ...projects.map(p => ({ value: p.id, label: p.name })),
  ], [projects, t.common.selectProject]);

  function handleSaveBlock() {
    if (!editingBlock) return;
    try {
      const config = JSON.parse(editingBlock.config) as Record<string, unknown>;
      const updated: BlockDefinition[] = [...editedBlocks];
      const prev = updated[editingBlock.index];
      if (!prev) return;
      updated[editingBlock.index] = { ...prev, config };
      setEditedBlocks(updated);
      setEditingBlock(null);
    } catch {
      toast.error(t.dialog.invalidJson);
    }
  }

  if (!suggestion.available) {
    return (
      <div className="flex flex-col items-center gap-2 py-6 text-center">
        <XCircle className="w-8 h-8 text-text-secondary/40" />
        <p className="text-xs text-text-secondary">{suggestion.notAvailableReason || t.dialog.notAvailable}</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {/* Badges mit Tooltips */}
      <div className="flex items-center gap-2 flex-wrap">
        <span title={t.dialog.confidenceTooltip}>
          <ConfidenceBadge confidence={suggestion.confidence} />
        </span>
        {suggestion.patternType && (
          <span title={t.dialog.patternTooltip}>
            <Badge variant="default">{suggestion.patternType}</Badge>
          </span>
        )}
        <span className="text-xs text-text-secondary" title={t.dialog.toolCallsTooltip}>
          {suggestion.toolCallsAnalyzed} {t.dialog.toolCalls}
        </span>
      </div>

      {suggestion.confidence < 0.7 && (
        <div className={`flex items-center gap-2 p-2 rounded-lg text-xs border ${
          suggestion.confidence < 0.5
            ? 'bg-danger/10 border-danger/20 text-danger'
            : 'bg-warning/10 border-warning/20 text-warning'
        }`}>
          <AlertCircle className="w-4 h-4 shrink-0" />
          {t.dialog.lowConfidenceWarning}
        </div>
      )}

      <BuildFindingsNote findings={suggestion.buildFindings} findingsEn={suggestion.buildFindingsEn} />

      {suggestion.suggestedDescription && (
        <p className="text-xs text-text-secondary leading-relaxed">{suggestion.suggestedDescription}</p>
      )}

      {/* Blocks — clickable + editable */}
      <div>
        <p className="text-xs font-medium text-text-secondary uppercase tracking-wide mb-1.5">{t.dialog.blocks}</p>
        <div className="flex flex-col gap-1">
          {editedBlocks.map((block, i) => {
            const wasEdited = block !== suggestion.blocks[i];
            const config = block.config as Record<string, unknown>;
            const preview = String(config?.command ?? JSON.stringify(config).slice(0, 80));
            return (
              <button
                key={i}
                onClick={() => setEditingBlock({
                  index: i,
                  config: JSON.stringify(block.config, null, 2),
                })}
                className="flex items-center gap-2 p-1.5 rounded-md bg-surface border border-border
                           text-xs w-full text-left hover:border-accent/50 hover:bg-surface-hover
                           transition-colors cursor-pointer group"
              >
                <Badge variant={blockTypeVariant[block.type] ?? 'default'}>{block.type}</Badge>
                <span className="text-text-secondary truncate font-mono flex-1">{preview}</span>
                {wasEdited && (
                  <Pencil className="w-3 h-3 text-accent shrink-0" />
                )}
                <ChevronRight className="w-3.5 h-3.5 text-text-secondary/50 shrink-0 group-hover:text-accent transition-colors" />
              </button>
            );
          })}
        </div>
      </div>

      {/* Block-Editor Modal */}
      {editingBlock && (
        <Modal
          open
          onClose={() => setEditingBlock(null)}
          title={`${editedBlocks[editingBlock.index]?.type ?? 'Block'} #${editingBlock.index + 1}`}
          size="lg"
          footer={
            <ModalFooter
              onCancel={() => setEditingBlock(null)}
              onAction={handleSaveBlock}
              cancelLabel={t.common.cancel}
              actionLabel={t.common.save}
            />
          }
        >
          <MonacoEditor
            value={editingBlock.config}
            onChange={(val) => setEditingBlock({ ...editingBlock, config: val ?? '' })}
            language="json"
            height="300px"
          />
        </Modal>
      )}

      {/* Workflow erstellen */}
      <div className="flex flex-col gap-2 pt-2 border-t border-border">
        <Input label={t.dialog.workflowName} value={name} onChange={e => setName(e.target.value)} />
        <Select
          label={t.common.selectProject}
          value={projectId}
          onChange={e => setProjectId(e.target.value)}
          options={projectOptions}
        />
        <Button
          variant="primary"
          onClick={() => onCreateWorkflow(projectId, name, editedBlocks)}
          disabled={!projectId || !name.trim() || isCreating}
          className="w-full"
        >
          {isCreating ? <Spinner size="sm" /> : <CheckCircle2 className="w-4 h-4" />}
          {t.dialog.createWorkflow}
        </Button>
      </div>
    </div>
  );
}
