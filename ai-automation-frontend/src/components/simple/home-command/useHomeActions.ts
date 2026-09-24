import { useNavigate } from 'react-router-dom';
import { useT, useI18nStore } from '../../../i18n';
import { useRetryExecution, useSubmitApproval } from '../../../hooks/useWorkflows';
import { useCreateTodo } from '../../../hooks/useTodos';
import { useOpenInPro } from '../../../hooks/useOpenInPro';
import { useToast } from '../../../hooks/useToast';
import { useSettingsStore } from '../../../stores/settingsStore';
import { useThemeStore } from '../../../stores/themeStore';
import { useSimpleRun } from '../useSimpleRun';
import type { HomeAction } from '../../../utils/homeCommand/types';

/**
 * Executes the command field's actions. `onDone` runs once an action has done its job and the query
 * can go (a task was added, a setting switched, a page opened); `onToggleExplanation` expands a hint.
 * Render `dialog` once — it asks for a workflow's inputs before a start.
 */
export function useHomeActions({ onDone, onToggleExplanation }: {
  onDone: () => void;
  onToggleExplanation: (hintId: string) => void;
}) {
  const t = useT();
  const navigate = useNavigate();
  const toast = useToast();
  const simpleRun = useSimpleRun();
  const retry = useRetryExecution();
  const submitApproval = useSubmitApproval();
  const createTodo = useCreateTodo();
  const openInPro = useOpenInPro();
  const setLocale = useI18nStore((s) => s.setLocale);
  const toggleAppearance = useThemeStore((s) => s.toggleAppearance);
  const resetHints = useSettingsStore((s) => s.resetHints);

  const execute = (action: HomeAction) => {
    switch (action.kind) {
      case 'navigate':
        onDone();
        navigate(action.to);
        return;
      case 'run-workflow':
        simpleRun.run(action.workflow);
        return;
      case 'retry-run':
        retry.mutate(action.runId, { onSuccess: (next) => navigate(`/workflows/${next.workflowId}/run/${next.id}`) });
        return;
      case 'answer-approval':
        submitApproval.mutate(
          { runId: action.runId, answer: action.answer },
          { onSuccess: () => toast.success(t.approvals.answered) },
        );
        return;
      case 'create-todo':
        createTodo.mutate({ title: action.title }, { onSuccess: onDone });
        return;
      case 'set-locale':
        setLocale(action.locale);
        onDone();
        return;
      case 'toggle-theme':
        toggleAppearance();
        onDone();
        return;
      case 'reset-hints':
        resetHints();
        toast.success(t.simple.home.command.hintsShown);
        onDone();
        return;
      case 'open-in-pro':
        onDone();
        openInPro(action.to);
        return;
      case 'toggle-explanation':
        onToggleExplanation(action.hintId);
        return;
    }
  };

  /** Whether this action is on its way (for the button's spinner). */
  const isPending = (action: HomeAction): boolean => {
    switch (action.kind) {
      case 'run-workflow': return simpleRun.isPending;
      case 'retry-run': return retry.isPending && retry.variables === action.runId;
      case 'answer-approval': return submitApproval.isPending && submitApproval.variables?.runId === action.runId;
      case 'create-todo': return createTodo.isPending;
      default: return false;
    }
  };

  return { execute, isPending, dialog: simpleRun.dialog };
}
