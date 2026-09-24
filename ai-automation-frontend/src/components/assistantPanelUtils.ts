import type { AssistantAction, AssistantPageContext } from '../hooks/useAssistant';
import type { TranslationKeys } from '../i18n';

export function parsePageContext(pathname: string): AssistantPageContext {
  const segments = pathname.replace(/^\//, '').split('/');

  if (segments[0] === 'workflows' && segments[2] === 'run') {
    return {
      page: 'run',
      entityId: segments[3],
      workflowId: segments[1],
      runId: segments[3],
      pathname,
    };
  }
  if (segments[0] === 'workflows' && segments[1]) {
    return {
      page: 'workflow',
      entityId: segments[1],
      workflowId: segments[1],
      pathname,
    };
  }
  if (segments[0] === 'projects' && segments[1]) {
    return {
      page: 'project',
      entityId: segments[1],
      projectId: segments[1],
      pathname,
    };
  }
  return { page: segments[0] || 'dashboard', pathname };
}

export function formatAssistantTime(ts?: number) {
  if (!ts) return null;
  const d = new Date(ts);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

export function formatAssistantResponseTime(ms?: number) {
  if (ms == null) return null;
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
}

/**
 * True nur fuer app-interne Pfade, die gefahrlos an react-routers navigate() gehen duerfen.
 *
 * Ein Pfad, der mit "//" oder "/\\" beginnt, ist protokollrelativ bzw. wird von Browsern und
 * aelteren react-router-Versionen als externes Ziel aufgeloest — "/\\evil.example" landet also
 * ausserhalb der App (GHSA open redirect, react-router <= 7.17.0). Assistenten-Antworten sind
 * LLM-Text und koennen untrusted Inhalte aus Workflow-Ausgaben oder Webhooks enthalten, deshalb
 * wird hier geprueft statt vertraut.
 */
export function isInternalAppPath(href: string | undefined): href is string {
  if (!href || !href.startsWith('/')) return false;
  const second = href[1];
  return second !== '/' && second !== '\\';
}

/** The actions worth a button: run/show actions without an id have nowhere to go. */
export function visibleActions(actions: AssistantAction[] | undefined): AssistantAction[] {
  return (actions ?? []).filter((a) => a.type === 'ask_choice' || a.type === 'start_wish' || a.id);
}

/** Text for a status phase pushed while the assistant works (`useAssistantWebSocket`). */
export function assistantPhaseLabel(phase: string, t: TranslationKeys): string {
  const labels: Record<string, string> = {
    RECEIVED: t.assistant.phaseReceived,
    BUILDING_CONTEXT: t.assistant.phaseBuildingContext,
    THINKING: t.assistant.phaseThinking,
    FETCHING_DATA: t.assistant.phaseFetchingData,
    EXECUTING_TOOLS: t.assistant.phaseExecutingTools,
    COMPLETED: t.assistant.phaseCompleted,
    ERROR: t.assistant.phaseError,
  };
  return labels[phase] ?? phase;
}
