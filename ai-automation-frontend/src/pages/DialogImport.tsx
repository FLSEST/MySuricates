import { Hint } from '../components/hints/Hint';
import { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  MessageCircle, CheckCircle2, AlertCircle, XCircle,
  Clock, ChevronRight, Sparkles, Layers, GitBranch, Pencil,
} from 'lucide-react';
import { Card, Button, Input, Select, Spinner, Badge, Toggle, Modal, ModalFooter } from '../components/ui';
import { MonacoEditor } from '../components/shared/MonacoEditor';
import { BuildFindingsNote } from '../components/shared/BuildFindingsNote';
import { useProjects } from '../hooks/useProjects';
import {
  useDialogSessionCounts, useDialogSessions,
  useAnalyzeDialog, useCreateWorkflowFromDialog,
} from '../hooks/useDialog';
import { useWorkflows } from '../hooks/useWorkflows';
import { useI18nStore, useT, type Locale } from '../i18n';
import { formatDate } from '../utils/localeFormat';
import { useToast } from '../hooks/useToast';
import { WorkflowIcon } from '../utils/workflowIcon';
import type { DialogSessionInfo, DialogWorkflowSuggestion, Workflow, BlockDefinition } from '../types';
import type { BadgeVariant } from '../components/ui/Badge';

const OTHER_KEY = '__other__';

const blockTypeVariant: Record<string, BadgeVariant> = {
  simple: 'success', agentic: 'success', 'skill-capability': 'success',
  shell: 'accent', 'sub-workflow': 'accent',
  approval: 'warning', 'human-input': 'warning',
  retry: 'default', foreach: 'default', while: 'default', conditional: 'default',
  compress: 'accent', 'compress-ai': 'success',
};

// ── Hilfsfunktionen ──────────────────────────────────────

function formatDuration(start: string | null, end: string | null): string {
  if (!start || !end) return '?';
  const ms = new Date(end).getTime() - new Date(start).getTime();
  const mins = Math.floor(ms / 60000);
  if (mins < 1) return '<1 min';
  if (mins < 60) return `${mins} min`;
  return `${Math.floor(mins / 60)}h ${mins % 60}min`;
}

function formatSessionStart(iso: string | null, locale: Locale): string {
  if (!iso) return '?';
  return formatDate(iso, locale, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function projectShortName(path: string): string {
  if (!path) return '?';
  const parts = path.split('/');
  return parts[parts.length - 1] || path;
}

function ConfidenceBadge({ confidence }: { confidence: number }) {
  const pct = Math.round(confidence * 100);
  if (confidence >= 0.7) return <Badge variant="success">{pct}%</Badge>;
  if (confidence >= 0.5) return <Badge variant="warning">{pct}%</Badge>;
  return <Badge variant="danger">{pct}%</Badge>;
}

// ── Typen ─────────────────────────────────────────────────

interface ProjectGroup {
  id: string;
  label: string;
  path: string | null;
  count: number;
  isOther: boolean;
}

// ── Panel 1: Projekt-Sidebar ──────────────────────────────

function ProjectSidebar({
  groups, selectedId, onSelect,
}: {
  groups: ProjectGroup[];
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

// ── Panel 2: Session-Karte ────────────────────────────────

function SessionCard({
  session, selected, onClick,
}: {
  session: DialogSessionInfo;
  selected: boolean;
  onClick: () => void;
}) {
  const locale = useI18nStore((s) => s.locale);
  return (
    <button
      onClick={onClick}
      className={`w-full text-left p-3 rounded-lg border transition-colors ${
        selected
          ? 'border-accent bg-accent/10'
          : 'border-border bg-surface hover:border-accent/50 hover:bg-surface-hover'
      }`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="flex-1 min-w-0">
          {session.firstUserMessage && (
            <p className="text-xs text-text truncate leading-relaxed mb-1">
              {session.firstUserMessage}
            </p>
          )}
          <div className="flex items-center gap-1.5 flex-wrap">
            {session.dominantTools.map(tool => (
              <span key={tool} className="text-xs px-1.5 py-0.5 rounded bg-surface-elevated text-text-secondary font-mono">
                {tool}
              </span>
            ))}
            {!session.substantial && <Badge variant="default">small</Badge>}
          </div>
        </div>
        <div className="flex flex-col items-end gap-1 shrink-0 text-xs text-text-secondary">
          <span>{formatSessionStart(session.startedAt, locale)}</span>
          <span>{formatDuration(session.startedAt, session.endedAt)}</span>
          <ChevronRight className="w-3.5 h-3.5 text-text-secondary/50 mt-0.5" />
        </div>
      </div>
    </button>
  );
}

// ── Panel 3: Bestehende Workflows ─────────────────────────

function ExistingWorkflowsList({ workflows }: { workflows: Workflow[] }) {
  const t = useT();
  const navigate = useNavigate();
  const MAX_VISIBLE = 5;
  const visible = workflows.slice(0, MAX_VISIBLE);
  const rest = workflows.length - MAX_VISIBLE;

  if (workflows.length === 0) {
    return <p className="text-xs text-text-secondary italic">{t.dialog.noExistingWorkflows}</p>;
  }

  return (
    <div className="flex flex-col gap-1">
      {visible.map(w => (
        <button
          key={w.id}
          onClick={() => navigate(`/workflows/${w.id}`)}
          className="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-surface-hover text-left group"
        >
          <WorkflowIcon icon={w.icon} className="w-3.5 h-3.5 text-text-secondary shrink-0" />
          <span className="text-xs text-text truncate group-hover:text-accent transition-colors">
            {w.name}
          </span>
        </button>
      ))}
      {rest > 0 && (
        <span className="text-xs text-text-secondary px-2">+{rest} {t.dialog.moreWorkflows}</span>
      )}
    </div>
  );
}

// ── Panel 3: Suggestion ───────────────────────────────────

function SuggestionPanel({
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
            ? 'bg-red-500/10 border-red-500/20 text-red-400'
            : 'bg-amber-500/10 border-amber-500/20 text-amber-400'
        }`}>
          <AlertCircle className="w-4 h-4 shrink-0" />
          {t.dialog.lowConfidenceWarning}
        </div>
      )}

      <BuildFindingsNote findings={suggestion.buildFindings} />

      {suggestion.suggestedDescription && (
        <p className="text-xs text-text-secondary leading-relaxed">{suggestion.suggestedDescription}</p>
      )}

      {/* Blöcke — klickbar + editierbar */}
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

// ── Hauptseite ────────────────────────────────────────────

export function DialogImport() {
  const t = useT();
  const navigate = useNavigate();
  const toast = useToast();

  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(null);
  const [selectedSession, setSelectedSession] = useState<DialogSessionInfo | null>(null);
  const [suggestion, setSuggestion] = useState<DialogWorkflowSuggestion | null>(null);
  const [search, setSearch] = useState('');
  const [substantialOnly, setSubstantialOnly] = useState(true);

  const { data: projects } = useProjects();
  const { data: counts, isLoading: countsLoading } = useDialogSessionCounts();
  const { data: allWorkflows } = useWorkflows();
  const analyzeMutation = useAnalyzeDialog();
  const createMutation = useCreateWorkflowFromDialog();

  // Aktives Projekt bestimmen
  const activeProjects = useMemo(
    () => (projects ?? []).filter(p => !p.archivedAt),
    [projects],
  );

  const knownProjectPaths = useMemo(
    () => new Set(activeProjects.map(p => p.path)),
    [activeProjects],
  );

  // Gruppen aus Projekten + Counts
  const groups = useMemo<ProjectGroup[]>(() => {
    if (!counts) return [];

    const projectGroups: ProjectGroup[] = activeProjects
      .map(p => ({
        id: p.id, label: p.name, path: p.path,
        count: counts[p.path] ?? 0, isOther: false,
      }))
      .filter(g => g.count > 0)
      .sort((a, b) => b.count - a.count);

    const otherCount = Object.entries(counts)
      .filter(([path]) => !knownProjectPaths.has(path))
      .reduce((sum, [, c]) => sum + c, 0);

    if (otherCount > 0) {
      projectGroups.push({
        id: OTHER_KEY, label: t.dialog.otherSessions,
        path: null, count: otherCount, isOther: true,
      });
    }
    return projectGroups;
  }, [counts, activeProjects, knownProjectPaths, t.dialog.otherSessions]);

  const selectedGroup = useMemo(
    () => groups.find(g => g.id === selectedGroupId) ?? null,
    [groups, selectedGroupId],
  );

  // Lazy-Load: Sessions nur für das gewählte Projekt
  const isProjectSelected = !!selectedGroup && !selectedGroup.isOther;
  const isOtherSelected = selectedGroup?.isOther === true;

  const { data: projectSessions, isLoading: sessionsLoading } = useDialogSessions(
    selectedGroup?.path ?? undefined,
    2000,
    { enabled: isProjectSelected },
  );

  // "Other Sessions": global laden, im Frontend filtern
  const { data: otherSessionsRaw, isLoading: otherLoading } = useDialogSessions(
    undefined, 500,
    { enabled: isOtherSelected },
  );
  const otherSessions = useMemo(() => {
    if (!otherSessionsRaw) return [];
    return otherSessionsRaw.filter(s => !knownProjectPaths.has(s.projectPath));
  }, [otherSessionsRaw, knownProjectPaths]);

  // Sessions für die Anzeige (gefiltert)
  const currentSessions = useMemo(
    () => (isOtherSelected ? otherSessions : (projectSessions ?? [])),
    [isOtherSelected, otherSessions, projectSessions],
  );
  const currentLoading = isOtherSelected ? otherLoading : sessionsLoading;

  const filteredSessions = useMemo(() => {
    return currentSessions.filter(s => {
      if (substantialOnly && !s.substantial) return false;
      if (search) {
        const q = search.toLowerCase();
        return s.firstUserMessage?.toLowerCase().includes(q) ?? false;
      }
      return true;
    });
  }, [currentSessions, substantialOnly, search]);

  // Workflows des gewählten Projekts
  const projectWorkflows = useMemo<Workflow[]>(() => {
    if (!selectedGroup || selectedGroup.isOther || !allWorkflows) return [];
    return allWorkflows.filter(w => w.projectId === selectedGroup.id);
  }, [selectedGroup, allWorkflows]);

  function handleSelectGroup(id: string) {
    setSelectedGroupId(id);
    setSelectedSession(null);
    setSuggestion(null);
    setSearch('');
  }

  async function handleAnalyze(session: DialogSessionInfo) {
    setSelectedSession(session);
    setSuggestion(null);
    try {
      const result = await analyzeMutation.mutateAsync(session.filePath);
      setSuggestion(result);
    } catch {
      toast.error(t.dialog.analyzeFailed);
    }
  }

  async function handleCreate(projectId: string, name: string, blocks?: BlockDefinition[]) {
    if (!suggestion) return;
    const finalSuggestion = blocks
      ? { ...suggestion, blocks }
      : suggestion;
    try {
      const workflow = await createMutation.mutateAsync({ suggestion: finalSuggestion, projectId, name });
      toast.success(t.dialog.created);
      navigate(`/workflows/${workflow.id}`);
    } catch {
      toast.error(t.dialog.createFailed);
    }
  }

  return (
    <div className="flex flex-col gap-4 p-6 max-w-screen-xl mx-auto">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="p-2 rounded-xl bg-accent/10">
          <MessageCircle className="w-6 h-6 text-accent" />
        </div>
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-xl font-semibold text-text">{t.dialog.title}</h1>
            <span className="text-xs px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-500 border border-amber-500/20 font-medium">
              {t.dialog.experimental}
            </span>
          </div>
          <p className="text-sm text-text-secondary">{t.dialog.subtitle}</p>
        </div>
      </div>

      <Hint id="dialogs-sessions" />

      {/* 3-Panel-Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 items-start">

        {/* Panel 1: Projekte */}
        <Card>
          <div className="p-3">
            <p className="text-xs font-semibold text-text-secondary uppercase tracking-wide mb-2 px-1">
              {t.nav.projects}
            </p>
            {countsLoading
              ? <div className="flex justify-center py-4"><Spinner size="sm" /></div>
              : groups.length === 0
                ? <p className="text-xs text-text-secondary px-1">{t.dialog.noSessions}</p>
                : <ProjectSidebar groups={groups} selectedId={selectedGroupId} onSelect={handleSelectGroup} />}
          </div>
        </Card>

        {/* Panel 2: Sessions */}
        <Card>
          <div className="p-3 flex flex-col gap-2">
            <p className="text-xs font-semibold text-text-secondary uppercase tracking-wide px-1">
              {t.dialog.sessions}
              {selectedGroup && <span className="ml-1 font-normal">— {selectedGroup.label}</span>}
            </p>

            {!selectedGroup ? (
              <div className="flex flex-col items-center gap-2 py-10 text-center">
                <Layers className="w-8 h-8 text-text-secondary/30" />
                <p className="text-xs text-text-secondary">{t.dialog.selectProjectFirst}</p>
              </div>
            ) : (
              <>
                <div className="flex items-center gap-2">
                  <div className="flex-1">
                    <Input
                      placeholder={t.common.search}
                      value={search}
                      onChange={e => setSearch(e.target.value)}
                    />
                  </div>
                  <div className="shrink-0 pt-4">
                    <Toggle enabled={substantialOnly} onChange={setSubstantialOnly} />
                  </div>
                </div>

                <div className="flex flex-col gap-1.5 max-h-[65vh] overflow-y-auto pr-0.5">
                  {currentLoading ? (
                    <div className="flex justify-center py-8"><Spinner size="sm" /></div>
                  ) : filteredSessions.length === 0 ? (
                    <div className="flex flex-col items-center gap-2 py-8 text-center">
                      <Clock className="w-7 h-7 text-text-secondary/30" />
                      <p className="text-xs text-text-secondary">
                        {substantialOnly ? t.dialog.noSubstantialSessions : t.dialog.noSessions}
                      </p>
                    </div>
                  ) : (
                    filteredSessions.map(s => (
                      <SessionCard
                        key={s.sessionId}
                        session={s}
                        selected={selectedSession?.sessionId === s.sessionId}
                        onClick={() => handleAnalyze(s)}
                      />
                    ))
                  )}
                </div>
              </>
            )}
          </div>
        </Card>

        {/* Panel 3: Detail (col-span-2) */}
        <div className="lg:col-span-2 flex flex-col gap-4">
          {selectedGroup && !selectedGroup.isOther && (
            <Card>
              <div className="p-3">
                <p className="text-xs font-semibold text-text-secondary uppercase tracking-wide mb-2">
                  {t.dialog.existingWorkflows}
                </p>
                <ExistingWorkflowsList workflows={projectWorkflows} />
              </div>
            </Card>
          )}

          <Card>
            <div className="p-4">
              {!selectedSession && !analyzeMutation.isPending && (
                <div className="flex flex-col items-center justify-center gap-3 py-12 text-center">
                  <MessageCircle className="w-10 h-10 text-text-secondary/30" />
                  <p className="text-sm text-text-secondary">{t.dialog.selectSession}</p>
                </div>
              )}

              {analyzeMutation.isPending && (
                <div className="flex flex-col items-center gap-3 py-12">
                  <Spinner />
                  <p className="text-sm text-text-secondary">{t.dialog.analyzing}</p>
                </div>
              )}

              {suggestion && !analyzeMutation.isPending && (
                <>
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <Sparkles className="w-4 h-4 text-accent" />
                      <span className="text-sm font-medium text-text">
                        {selectedSession && projectShortName(selectedSession.projectPath)}
                      </span>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => selectedSession && handleAnalyze(selectedSession)}
                    >
                      {t.dialog.reanalyze}
                    </Button>
                  </div>
                  <SuggestionPanel
                    suggestion={suggestion}
                    projects={activeProjects.map(p => ({ id: p.id, name: p.name }))}
                    onCreateWorkflow={handleCreate}
                    isCreating={createMutation.isPending}
                  />
                </>
              )}
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}
