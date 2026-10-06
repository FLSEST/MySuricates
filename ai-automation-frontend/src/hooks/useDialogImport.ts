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

/** Key of the group "Andere Sessions" (sessions without a known project). */
export const OTHER_DIALOG_GROUP = '__other__';

export interface DialogProjectGroup {
  id: string;
  label: string;
  path: string | null;
  count: number;
  isOther: boolean;
}

/**
 * State of the page "Session2Workflow": project groups from the session counts (active projects with
 * sessions, descending by count, plus "Andere Sessions" for unknown paths), the sessions of the
 * selected group (loaded only after the selection; "Andere" loads globally and filters here), search
 * and the filter "nur substanzielle", the project's workflows as well as analyzing and creating.
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

  // Determine the active project
  const activeProjects = useMemo(
    () => (projects ?? []).filter(p => !p.archivedAt),
    [projects],
  );

  const knownProjectPaths = useMemo(
    () => new Set(activeProjects.map(p => p.path)),
    [activeProjects],
  );

  // Groups from projects + counts
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

  // Lazy load: sessions only for the selected project
  const isProjectSelected = !!selectedGroup && !selectedGroup.isOther;
  const isOtherSelected = selectedGroup?.isOther === true;

  const { data: projectSessions, isLoading: sessionsLoading } = useDialogSessions(
    selectedGroup?.path ?? undefined,
    2000,
    { enabled: isProjectSelected },
  );

  // "Other Sessions": load globally, filter in the frontend
  const { data: otherSessionsRaw, isLoading: otherLoading } = useDialogSessions(
    undefined, 500,
    { enabled: isOtherSelected },
  );
  const otherSessions = useMemo(() => {
    if (!otherSessionsRaw) return [];
    return otherSessionsRaw.filter(s => !knownProjectPaths.has(s.projectPath));
  }, [otherSessionsRaw, knownProjectPaths]);

  // Sessions for display (filtered)
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

  // Workflows of the selected project
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
