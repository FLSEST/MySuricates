import { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useProjects } from './useProjects';
import {
  useDialogSessionCounts, useDialogSessions,
  useAnalyzeDialog, useCreateWorkflowFromDialog,
} from './useDialog';
import { useWorkflows } from './useWorkflows';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { DialogSessionInfo, DialogWorkflowSuggestion, Workflow, BlockDefinition } from '../types';

/** Schlüssel der Gruppe „Andere Sessions“ (Sessions ohne bekanntes Projekt). */
export const OTHER_DIALOG_GROUP = '__other__';

export interface DialogProjectGroup {
  id: string;
  label: string;
  path: string | null;
  count: number;
  isOther: boolean;
}

/**
 * Zustand der Seite „Session2Workflow“: Projektgruppen aus den Session-Zahlen (aktive Projekte mit
 * Sessions, absteigend nach Zahl, dazu „Andere Sessions“ für unbekannte Pfade), die Sessions der
 * gewählten Gruppe (erst nach der Auswahl geladen; „Andere“ lädt global und filtert hier), Suche
 * und Filter „nur substanzielle“, die Workflows des Projekts sowie Analysieren und Anlegen.
 */
export function useDialogImport() {
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
  const groups = useMemo<DialogProjectGroup[]>(() => {
    if (!counts) return [];

    const projectGroups: DialogProjectGroup[] = activeProjects
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
        id: OTHER_DIALOG_GROUP, label: t.dialog.otherSessions,
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
  const sessionsLoadingNow = isOtherSelected ? otherLoading : sessionsLoading;

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

  function selectGroup(id: string) {
    setSelectedGroupId(id);
    setSelectedSession(null);
    setSuggestion(null);
    setSearch('');
  }

  async function analyze(session: DialogSessionInfo) {
    setSelectedSession(session);
    setSuggestion(null);
    try {
      const result = await analyzeMutation.mutateAsync(session.filePath);
      setSuggestion(result);
    } catch {
      toast.error(t.dialog.analyzeFailed);
    }
  }

  async function create(projectId: string, name: string, blocks?: BlockDefinition[]) {
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

  return {
    activeProjects,
    groups, countsLoading, selectedGroupId, selectedGroup, selectGroup,
    search, setSearch, substantialOnly, setSubstantialOnly,
    filteredSessions, sessionsLoading: sessionsLoadingNow,
    projectWorkflows,
    selectedSession, suggestion, analyze, analyzing: analyzeMutation.isPending,
    create, creating: createMutation.isPending,
  };
}
