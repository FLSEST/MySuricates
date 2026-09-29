import { useState } from 'react';
import { Sparkles, ChevronDown, ChevronUp, Plus } from 'lucide-react';
import { Card, Button, Badge, Input } from '../ui';
import { BuildFindingsNote } from './BuildFindingsNote';
import { useConversionSuggestion, useCreateWorkflowFromSuggestion } from '../../hooks/useConversion';
import { useProjects } from '../../hooks/useProjects';
import { useT } from '../../i18n';
import { useToastStore } from '../../stores/toastStore';

export function ConversionSuggestion({ runId }: { runId: string }) {
  const t = useT();
  const addToast = useToastStore((s) => s.addToast);
  const { data: suggestion, isLoading } = useConversionSuggestion(runId);
  const { data: projects } = useProjects();
  const createWorkflow = useCreateWorkflowFromSuggestion();

  const [expanded, setExpanded] = useState(false);
  const [selectedProjectId, setSelectedProjectId] = useState('');
  const [customName, setCustomName] = useState('');

  if (isLoading || !suggestion?.available) return null;

  const handleCreate = () => {
    if (!selectedProjectId) return;

    // Den angezeigten Vorschlag mitschicken: Gespeichert wird genau das Geprüfte,
    // ohne den Vorschlag serverseitig neu (und mit KI-Aufruf) zu erzeugen (TODO 935af6d3).
    // Der Server verlangt dazu einen Namen; ohne jeden Namen bleibt es beim alten Weg.
    const name = customName || suggestion.suggestedName || undefined;
    createWorkflow.mutate(
      {
        runId,
        projectId: selectedProjectId,
        name,
        ...(name ? { description: suggestion.suggestedDescription, blocks: suggestion.blocks } : {}),
      },
      {
        onSuccess: () => {
          addToast('success', t.conversion.created);
        },
        onError: () => {
          addToast('error', t.conversion.createFailed);
        },
      }
    );
  };

  return (
    <Card className="border-accent/30 bg-accent/5">
      <div className="flex items-center gap-3">
        <div className="p-2 rounded-lg bg-accent/10">
          <Sparkles className="w-5 h-5 text-accent" />
        </div>
        <div className="flex-1">
          <p className="text-sm font-medium text-text">{t.conversion.banner}</p>
          <p className="text-xs text-text-secondary mt-0.5">
            {suggestion.agenticStepsAnalyzed} {t.conversion.stepsAnalyzed}
          </p>
        </div>
        <Button
          variant="secondary"
          size="sm"
          icon={expanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
          onClick={() => setExpanded(!expanded)}
        >
          {t.conversion.preview}
        </Button>
      </div>

      {expanded && (
        <div className="mt-4 space-y-4">
          {/* Goal */}
          {suggestion.originalGoal && (
            <div>
              <p className="text-xs font-semibold uppercase tracking-wider text-text-tertiary mb-1">
                {t.conversion.originalGoal}
              </p>
              <p className="text-sm text-text-secondary">{suggestion.originalGoal}</p>
            </div>
          )}

          <BuildFindingsNote findings={suggestion.buildFindings} findingsEn={suggestion.buildFindingsEn} />

          {/* Suggested blocks */}
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-text-tertiary mb-2">
              {t.conversion.blocks} ({suggestion.blocks.length})
            </p>
            <div className="space-y-1">
              {suggestion.blocks.map((block, i) => (
                <div key={i} className="flex items-center gap-2 p-2 rounded-sm bg-surface text-sm">
                  <span className="text-text-tertiary font-mono text-xs">#{i + 1}</span>
                  <Badge variant="default">{block.type}</Badge>
                  <span className="text-text-secondary truncate font-mono text-xs">
                    {String(block.config?.command ?? '')}
                  </span>
                </div>
              ))}
            </div>
          </div>

          {/* Create form */}
          <div className="border-t border-border pt-4 space-y-3">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className="text-xs text-text-secondary block mb-1">{t.conversion.selectProject}</label>
                <select
                  value={selectedProjectId}
                  onChange={(e) => setSelectedProjectId(e.target.value)}
                  className="w-full px-3 py-2 text-sm rounded-lg bg-surface border border-border text-text"
                >
                  <option value="">{t.common.selectProject}</option>
                  {projects?.map((p) => (
                    <option key={p.id} value={p.id}>{p.name}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="text-xs text-text-secondary block mb-1">{t.conversion.suggestedName}</label>
                <Input
                  value={customName}
                  onChange={(e) => setCustomName(e.target.value)}
                  placeholder={suggestion.suggestedName ?? ''}
                />
              </div>
            </div>
            <Button
              icon={<Plus className="w-4 h-4" />}
              onClick={handleCreate}
              loading={createWorkflow.isPending}
              disabled={!selectedProjectId}
            >
              {t.conversion.createWorkflow}
            </Button>
          </div>
        </div>
      )}
    </Card>
  );
}
