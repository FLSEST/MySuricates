import { useNavigate } from 'react-router-dom';
import { Loader2, MessageSquare, RefreshCw, Sparkles, Square, X } from 'lucide-react';
import { useT } from '../../../i18n';
import { useModuleEnabled } from '../../../hooks/useModules';
import { useAssistantWebSocket } from '../../../hooks/useAssistantWebSocket';
import { MarkdownBlock } from '../../shared/MarkdownBlock';
import { ActionButton } from '../../assistant/ActionButton';
import { PendingToolCard } from '../../assistant/PendingToolCard';
import { assistantPhaseLabel, visibleActions } from '../../assistantPanelUtils';
import type { HomeAnswerTurn } from './useHomeAssistant';

interface HomeAnswerProps {
  turn: HomeAnswerTurn;
  onCancel: () => void;
  onRetry: () => void;
  onClose: () => void;
  onContinueInChat: () => void;
  /** An ask_choice option: sent as the next message of the same conversation. */
  onSend: (text: string) => void;
  /** run_workflow: start through the Simple start (asks for inputs first when the workflow has some). */
  onRunWorkflow: (workflowId: string) => void;
}

/**
 * The assistant's answer at the top of the home field's results: the question, the answer as
 * Markdown, its actions as buttons and proposed changes to confirm — the same pieces as a message
 * in the assistant panel. While it works: its status and Cancel; on failure: Try again.
 */
export function HomeAnswer({ turn, onCancel, onRetry, onClose, onContinueInChat, onSend, onRunWorkflow }: HomeAnswerProps) {
  const t = useT();
  const a = t.simple.home.command.answer;
  const navigate = useNavigate();
  const wishesModule = useModuleEnabled('wishes');
  const { currentPhase } = useAssistantWebSocket(turn.requestId, null);
  const { reply } = turn;
  const actions = visibleActions(reply?.actions).filter((action) => action.type !== 'start_wish' || wishesModule);

  const retryButton = (
    <button
      type="button"
      onClick={onRetry}
      className="inline-flex items-center gap-1 text-xs font-medium text-accent hover:underline"
    >
      <RefreshCw className="w-3 h-3" aria-hidden />
      {a.retry}
    </button>
  );

  return (
    <section aria-label={a.label} className="rounded-xl border border-ai/30 bg-ai-subtle px-3 py-3 sm:px-4 space-y-2">
      <div className="flex items-start gap-2">
        <Sparkles className="w-4 h-4 text-ai shrink-0 mt-0.5" aria-hidden />
        <p className="flex-1 min-w-0 text-xs text-text-secondary break-words">{turn.question}</p>
        <button
          type="button"
          onClick={onClose}
          aria-label={a.close}
          className="p-1 -m-1 rounded text-text-tertiary hover:text-text transition-colors shrink-0"
        >
          <X className="w-4 h-4" aria-hidden />
        </button>
      </div>

      {turn.state === 'pending' && (
        <div className="flex items-center gap-2 text-sm text-text-secondary">
          <Loader2 className="w-4 h-4 text-ai animate-spin shrink-0" aria-hidden />
          <span role="status" className="flex-1 min-w-0">
            {currentPhase ? assistantPhaseLabel(currentPhase.phase, t) : t.assistant.phaseThinking}
          </span>
          <button
            type="button"
            onClick={onCancel}
            className="inline-flex items-center gap-1 text-xs text-text-tertiary hover:text-text-secondary shrink-0"
          >
            <Square className="w-3 h-3" aria-hidden />
            {t.common.cancel}
          </button>
        </div>
      )}

      {turn.state === 'answered' && reply && (
        <>
          <MarkdownBlock noImages onInternalLink={navigate}>{reply.content}</MarkdownBlock>
          {reply.pendingTools && reply.pendingTools.length > 0 && (
            <div className="flex flex-col gap-1.5">
              {reply.pendingTools.map((tool) => (
                <PendingToolCard key={tool.id} tool={tool} sessionId={turn.sessionId} />
              ))}
            </div>
          )}
          {actions.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {actions.map((action, i) => (
                <ActionButton key={i} action={action} onClose={onClose} onSend={onSend} onExecute={onRunWorkflow} />
              ))}
            </div>
          )}
        </>
      )}

      {turn.state === 'error' && (
        <div className="flex flex-wrap items-center gap-3">
          <p role="alert" className="text-sm text-danger">{a.error}</p>
          {retryButton}
        </div>
      )}

      {turn.state === 'cancelled' && (
        <div className="flex flex-wrap items-center gap-3">
          <p className="text-sm text-text-secondary">{a.cancelled}</p>
          {retryButton}
        </div>
      )}

      <div className="flex justify-end">
        <button
          type="button"
          onClick={onContinueInChat}
          className="inline-flex items-center gap-1.5 text-xs font-medium text-ai hover:underline"
        >
          <MessageSquare className="w-3.5 h-3.5" aria-hidden />
          {a.continueInChat}
        </button>
      </div>
    </section>
  );
}
