import { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  MessageCircle, CheckCircle2, AlertCircle, XCircle,
  Clock, ChevronRight, Sparkles, Layers, GitBranch,
} from 'lucide-react';
import { Card, Button, Input, Select, Spinner, Badge, Toggle } from '../components/ui';
import { useProjects } from '../hooks/useProjects';
import { useDialogSessions, useAnalyzeDialog, useCreateWorkflowFromDialog } from '../hooks/useDialog';
import { useWorkflows } from '../hooks/useWorkflows';
import { useT } from '../i18n';
import { useToast } from '../hooks/useToast';
import { WorkflowIcon } from '../utils/workflowIcon';
import type { DialogSessionInfo, DialogWorkflowSuggestion, Workflow } from '../types';

const OTHER_KEY = '__other__';

// ── Hilfsfunktionen ──────────────────────────────────────

function formatDuration(start: string | null, end: string | null): string {
  if (!start || !end) return '?';
  const ms = new Date(end).getTime() - new Date(start).getTime();
  const mins = Math.floor(ms / 60000);
  if (mins < 1) return '<1 min';
  if (mins < 60) return `${mins} min`;
  return `${Math.floor(mins / 60)}h ${mins % 60}min`;
}

function formatDate(iso: string | null): string {
  if (!iso) return '?';
  return new Date(iso).toLocaleDateString(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

function projectShortName(path: string): string {
  if (!path) return '?';
  const parts = path.split('/');
  return parts[parts.length - 1] || path;
}

// ── Konfidenz-Badge ───────────────────────────────────────

function ConfidenceBadge({ confidence }: { confidence: number }) {
  const pct = Math.round(confidence * 100);
  if (confidence >= 0.7) return <Badge variant="success">{pct}%</Badge>;
  if (confidence >= 0.5) return <Badge variant="warning">{pct}%</Badge>;
  return <Badge variant="danger">{pct}%</Badge>;
}

// ── Panel 1: Projekt-Sidebar ──────────────────────────────

interface ProjectGroup {
  id: string;            // project.id or OTHER_KEY
  label: string;
  path: string | null;
  sessions: DialogSessionInfo[];
  isOther: boolean;
}

function ProjectSidebar({
  groups,
  selectedId,
  onSelect,
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
                : <GitBranch className="w-4 h-4 shrink-0 text-text-secondary" />
              }
              <span className="text-sm font-medium truncate">{g.label}</span>
            </div>
            <Badge variant={g.sessions.length > 0 ? 'default' : 'default'}>
              {g.sessions.length}
            </Badge>
          </button>
        );
      })}
    </div>
  );
}

// ── Panel 2: Session-Liste ────────────────────────────────

function SessionCard({
  session,
  selected,
  onClick,
}: {
  session: DialogSessionInfo;
  selected: boolean;
  onClick: () => void;
}) {
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
            {!session.substantial && (
              <Badge variant="default">small</Badge>
            )}
          </div>
        </div>
        <div className="flex flex-col items-end gap-1 shrink-0 text-xs text-text-secondary">
          <span>{formatDate(session.startedAt)}</span>
          <span>{formatDuration(session.startedAt, session.endedAt)}</span>
          <ChevronRight className="w-3.5 h-3.5 text-text-secondary/50 mt-0.5" />
        </div>
      </div>
    </button>
  );
}

// ── Panel 3: Detail (Workflows + Analyse) ────────────────

function ExistingWorkflowsList({ workflows }: { workflows: Workflow[] }) {
  const t = useT();
  const navigate = useNavigate();
  const MAX_VISIBLE = 5;
  const visible = workflows.slice(0, MAX_VISIBLE);
  const rest = workflows.length - MAX_VISIBLE;

  if (workflows.length === 0) {
    return (
      <p className="text-xs text-text-secondary italic">{t.dialog.noExistingWorkflows}</p>
    );
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

function SuggestionPanel({
  suggestion,
  projects,
  onCreateWorkflow,
  isCreating,
}: {
  suggestion: DialogWorkflowSuggestion;
  projects: { id: string; name: string }[];
  onCreateWorkflow: (projectId: string, name: string) => void;
  isCreating: boolean;
}) {
  const t = useT();
  const [projectId, setProjectId] = useState('');
  const [name, setName] = useState(suggestion.suggestedName ?? '');

  const projectOptions = useMemo(() => [
    { value: '', label: t.common.selectProject },
    ...projects.map(p => ({ value: p.id, label: p.name })),
  ], [projects, t.common.selectProject]);

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
      <div className="flex items-center gap-2 flex-wrap">
        <ConfidenceBadge confidence={suggestion.confidence} />
        {suggestion.patternType && <Badge variant="default">{suggestion.patternType}</Badge>}
        <span className="text-xs text-text-secondary">{suggestion.toolCallsAnalyzed} {t.dialog.toolCalls}</span>
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

      {suggestion.suggestedDescription && (
        <p className="text-xs text-text-secondary leading-relaxed">{suggestion.suggestedDescription}</p>
      )}

      <div>
        <p className="text-xs font-medium text-text-secondary uppercase tracking-wide mb-1.5">{t.dialog.blocks}</p>
        <div className="flex flex-col gap-1">
          {suggestion.blocks.map((block, i) => (
            <div key={i} className="flex items-center gap-2 p-1.5 rounded-md bg-surface border border-border text-xs">
              <Badge variant="default">{block.type}</Badge>
              <span className="text-text-secondary truncate font-mono">
                {String((block.config as Record<string, unknown>)?.command ?? '')}
              </span>
            </div>
          ))}
        </div>
      </div>

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
          onClick={() => onCreateWorkflow(projectId, name)}
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
  const { data: allSessions, isLoading: sessionsLoading } = useDialogSessions(undefined, 200);
  const { data: allWorkflows } = useWorkflows();
  const analyzeMutation = useAnalyzeDialog();
  const createMutation = useCreateWorkflowFromDialog();

  // Alle Sessions nach MySuricates-Projekten gruppieren
  const groups = useMemo<ProjectGroup[]>(() => {
    if (!allSessions) return [];

    const activeProjects = (projects ?? []).filter(p => !p.archivedAt);
    const usedPaths = new Set<string>();

    const projectGroups: ProjectGroup[] = activeProjects
      .map(p => {
        const sessions = allSessions.filter(s => s.projectPath === p.path);
        sessions.forEach(s => usedPaths.add(s.projectPath));
        return {
          id: p.id,
          label: p.name,
          path: p.path,
          sessions,
          isOther: false,
        };
      })
      .filter(g => g.sessions.length > 0);

    const other = allSessions.filter(s => !usedPaths.has(s.projectPath));
    if (other.length > 0) {
      projectGroups.push({
        id: OTHER_KEY,
        label: t.dialog.otherSessions,
        path: null,
        sessions: other,
        isOther: true,
      });
    }

    return projectGroups;
  }, [allSessions, projects, t.dialog.otherSessions]);

  // Sessions für gewählte Gruppe (gefiltert)
  const selectedGroup = useMemo(
    () => groups.find(g => g.id === selectedGroupId) ?? null,
    [groups, selectedGroupId]
  );

  const filteredSessions = useMemo(() => {
    if (!selectedGroup) return [];
    return selectedGroup.sessions.filter(s => {
      if (substantialOnly && !s.substantial) return false;
      if (search) {
        const q = search.toLowerCase();
        return s.firstUserMessage?.toLowerCase().includes(q) ?? false;
      }
      return true;
    });
  }, [selectedGroup, substantialOnly, search]);

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

  async function handleCreate(projectId: string, name: string) {
    if (!suggestion) return;
    try {
      const workflow = await createMutation.mutateAsync({ suggestion, projectId, name });
      toast.success(t.dialog.created);
      navigate(`/workflows/${workflow.id}`);
    } catch {
      toast.error(t.dialog.createFailed);
    }
  }

  const allProjectsForForm = (projects ?? []).map(p => ({ id: p.id, name: p.name }));

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

      {/* 3-Panel-Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 items-start">

        {/* Panel 1: Projekte */}
        <Card>
          <div className="p-3">
            <p className="text-xs font-semibold text-text-secondary uppercase tracking-wide mb-2 px-1">
              {t.nav.projects}
            </p>
            {sessionsLoading
              ? <div className="flex justify-center py-4"><Spinner size="sm" /></div>
              : groups.length === 0
                ? <p className="text-xs text-text-secondary px-1">{t.dialog.noSessions}</p>
                : <ProjectSidebar groups={groups} selectedId={selectedGroupId} onSelect={handleSelectGroup} />
            }
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
                {/* Filter-Leiste */}
                <div className="flex items-center gap-2">
                  <div className="flex-1">
                    <Input
                      placeholder={t.common.search}
                      value={search}
                      onChange={e => setSearch(e.target.value)}
                    />
                  </div>
                  <div className="shrink-0 pt-4">
                    <Toggle
                      enabled={substantialOnly}
                      onChange={setSubstantialOnly}
                    />
                  </div>
                </div>

                <div className="flex flex-col gap-1.5 max-h-[65vh] overflow-y-auto pr-0.5">
                  {filteredSessions.length === 0 ? (
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

          {/* Bestehende Workflows */}
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

          {/* Analyse / Suggestion */}
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
                    projects={allProjectsForForm}
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
