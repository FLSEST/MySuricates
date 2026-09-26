import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Play, Eye, Sparkles, ChevronDown, ChevronUp, RefreshCw, CheckCheck, XCircle, MessageCircle, Check, X, Loader2, ShieldAlert } from 'lucide-react';
import { CopyButton } from '../shared/CopyButton';
import { MeerkatIcon } from '../layout/MeerkatIcon';
import { formatAssistantTime, formatAssistantResponseTime, visibleActions } from '../assistantPanelUtils';
import { isInternalAppPath } from '../../utils/internalAppPath';
import { useI18nStore, useT } from '../../i18n';
import { apiErrorMessage } from '../../api/client';
import type { ChatMessage, AssistantAction, PendingTool, ToolExecutionResult } from '../../hooks/useAssistant';
import { useConfirmPendingTool, useRejectPendingTool } from '../../hooks/useAssistant';
import { isDestructivePendingTool, pendingToolText } from './pendingToolText';

/** One action of an answer as a button; ask_choice renders one button per option. */
export function ActionButton({ action, onClose, onSend, onExecute }: {
  action: AssistantAction;
  onClose: () => void;
  onSend?: (text: string) => void;
  onExecute?: (workflowId: string) => void;
}) {
  const navigate = useNavigate();

  // ask_choice: render each option as its own button
  if (action.type === 'ask_choice' && action.options) {
    return (
      <>
        {action.options.map((opt) => (
          <button
            key={opt}
            onClick={() => onSend?.(opt)}
            className="flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium
                       bg-accent/15 text-accent hover:bg-accent/25 transition-colors border border-accent/20"
          >
            <MessageCircle className="w-3 h-3" />
            {opt}
          </button>
        ))}
      </>
    );
  }

  const icon = action.type === 'run_workflow' ? <Play className="w-3 h-3" />
    : action.type === 'show_workflow' || action.type === 'show_run' ? <Eye className="w-3 h-3" />
    : <Sparkles className="w-3 h-3" />;

  const handleClick = () => {
    switch (action.type) {
      case 'run_workflow':
        if (action.id) onExecute?.(action.id);
        break;
      case 'show_workflow':
        navigate(`/workflows/${action.id}`);
        onClose();
        break;
      case 'show_run':
        if (action.id) navigate(`/runs/${action.id}`);
        else navigate('/runs');
        onClose();
        break;
      case 'start_wish':
        navigate('/wishes', { state: { prefill: action.name } });
        onClose();
        break;
    }
  };

  return (
    <button
      onClick={handleClick}
      className="flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium
                 bg-accent/15 text-accent hover:bg-accent/25 transition-colors border border-accent/20"
    >
      {icon}
      {action.label}
    </button>
  );
}

/**
 * A tool proposal awaiting user confirmation. The backend only executes the tool after the
 * user clicks "Bestätigen" — the assistant has no path to auto-trigger it regardless of the
 * model's prompt compliance.
 */
export function PendingToolCard({ tool, sessionId, existingResult }: {
  tool: PendingTool;
  sessionId?: string;
  existingResult?: ToolExecutionResult;
}) {
  const t = useT();
  const confirm = useConfirmPendingTool();
  const reject = useRejectPendingTool();
  const [localResult, setLocalResult] = useState<ToolExecutionResult | undefined>(existingResult);
  const [error, setError] = useState<string | null>(null);
  // Compute expiry once on mount — the pending-tool UI is short-lived; if the user stays on the
  // card past its 30-min TTL the backend's 410 Gone will surface via error state instead.
  const [expired] = useState<boolean>(() => {
    const expiresMs = tool.expiresAt ? new Date(tool.expiresAt).getTime() : 0;
    return expiresMs > 0 && expiresMs < Date.now();
  });
  const pending = confirm.isPending || reject.isPending;
  const destructive = isDestructivePendingTool(tool);

  if (localResult) {
    return (
      <div className={`flex items-start gap-1.5 text-xs rounded-lg px-2.5 py-1.5 border ${
        localResult.success
          ? 'bg-accent/10 border-accent/30 text-accent'
          : 'bg-danger/10 border-danger/30 text-danger'
      }`}>
        {localResult.success ? <Check className="w-3.5 h-3.5 mt-0.5 shrink-0" /> : <X className="w-3.5 h-3.5 mt-0.5 shrink-0" />}
        <span className="leading-snug">{localResult.message}</span>
      </div>
    );
  }

  const runConfirm = async () => {
    setError(null);
    try {
      const result = await confirm.mutateAsync({ id: tool.id, sessionId });
      setLocalResult(result);
    } catch (e) {
      setError(apiErrorMessage(e, t.assistant.pendingToolError));
    }
  };

  const runReject = async () => {
    setError(null);
    try {
      const result = await reject.mutateAsync({ id: tool.id, sessionId });
      setLocalResult(result);
    } catch (e) {
      setError(apiErrorMessage(e, t.assistant.pendingToolError));
    }
  };

  return (
    <div className="flex flex-col gap-1.5 text-xs rounded-lg px-2.5 py-1.5 border border-[var(--color-assistant-border)] bg-[var(--color-assistant-raised)]">
      <div className="flex items-start gap-1.5">
        <ShieldAlert className={`w-3.5 h-3.5 mt-0.5 shrink-0 ${destructive ? 'text-danger' : 'text-accent'}`} />
        <span className="leading-snug text-text">{pendingToolText(tool, t)}</span>
      </div>
      {expired ? (
        <div className="text-text-tertiary italic">{t.assistant.pendingToolExpired}</div>
      ) : (
        <div className="flex items-center gap-1.5">
          <button
            onClick={runConfirm}
            disabled={pending}
            className="flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium
                       bg-accent text-text-inverse hover:bg-accent-hover transition-colors disabled:opacity-60"
          >
            {confirm.isPending ? <Loader2 className="w-3 h-3 animate-spin" /> : <Check className="w-3 h-3" />}
            {confirm.isPending ? t.assistant.pendingToolExecuting : t.assistant.pendingToolConfirm}
          </button>
          <button
            onClick={runReject}
            disabled={pending}
            className="flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium
                       bg-transparent text-text-secondary border border-[var(--color-assistant-border)]
                       hover:bg-[var(--color-assistant-hover)] transition-colors disabled:opacity-60"
          >
            <X className="w-3 h-3" />
            {t.assistant.pendingToolReject}
          </button>
        </div>
      )}
      {error && <div className="text-danger text-[11px]">{error}</div>}
    </div>
  );
}

function MarkdownLink({ href, children, onNavigate }: {
  href?: string;
  children?: React.ReactNode;
  onNavigate: (path: string) => void;
}) {
  if (isInternalAppPath(href)) {
    return (
      <button
        onClick={() => onNavigate(href)}
        className="text-accent underline underline-offset-2 hover:text-accent-hover transition-colors"
      >
        {children}
      </button>
    );
  }
  return (
    <a href={href} target="_blank" rel="noopener noreferrer"
       className="text-accent underline underline-offset-2 hover:text-accent-hover">
      {children}
    </a>
  );
}

const COLLAPSE_THRESHOLD = 300;

export function MessageBubble({ msg, sessionId, onClose, onSend, onExecute, onRetry }: {
  msg: ChatMessage;
  sessionId?: string;
  onClose: () => void;
  onSend?: (text: string) => void;
  onExecute?: (workflowId: string) => void;
  onRetry?: () => void;
}) {
  const navigate = useNavigate();
  const locale = useI18nStore((s) => s.locale);
  const isUser = msg.role === 'user';
  const contentRef = useRef<HTMLDivElement>(null);
  const [isOverflowing, setIsOverflowing] = useState(false);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    if (!isUser && contentRef.current) {
      setIsOverflowing(contentRef.current.scrollHeight > COLLAPSE_THRESHOLD);
    }
  }, [msg.content, isUser]);

  const handleNavigate = (path: string) => {
    navigate(path);
    onClose();
  };

  const time = formatAssistantTime(msg.timestamp);
  const respTime = formatAssistantResponseTime(msg.responseTimeMs, locale);

  return (
    <div className={`flex gap-2 ${isUser ? 'flex-row-reverse' : 'flex-row'}`}>
      {/* Avatar */}
      <div className={`shrink-0 w-7 h-7 rounded-full flex items-center justify-center mt-0.5
                       ${isUser
                         ? 'bg-accent/20'
                         : 'bg-[var(--color-assistant-raised)] border border-[var(--color-assistant-border)]'
                       }`}>
        {isUser
          ? <span className="text-[10px] font-bold text-accent">Du</span>
          : <MeerkatIcon className="text-accent" size={18} />
        }
      </div>

      <div className={`group/msg flex flex-col gap-1 max-w-[85%] ${isUser ? 'items-end' : 'items-start'}`}>
        {/* Bubble */}
        <div className={`relative px-3 py-2 rounded-2xl text-sm leading-relaxed
                         ${isUser
                           ? 'bg-accent text-text-inverse rounded-tr-sm whitespace-pre-wrap'
                           : msg.isError
                             ? 'bg-[var(--color-assistant-raised)] border border-danger/50 text-text rounded-tl-sm'
                             : 'bg-[var(--color-assistant-raised)] border border-[var(--color-assistant-border)] text-text rounded-tl-sm'
                         }`}>
          {isUser ? (
            msg.content
          ) : (
            <div className="relative">
              <div
                ref={contentRef}
                className={!expanded && isOverflowing ? 'max-h-[300px] overflow-hidden' : undefined}
              >
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  components={{
                    a: ({ href, children }) => (
                      <MarkdownLink href={href} onNavigate={handleNavigate}>{children}</MarkdownLink>
                    ),
                    p: ({ children }) => <p className="mb-2 last:mb-0">{children}</p>,
                    ul: ({ children }) => <ul className="list-disc list-inside mb-2 space-y-0.5">{children}</ul>,
                    ol: ({ children }) => <ol className="list-decimal list-inside mb-2 space-y-0.5">{children}</ol>,
                    li: ({ children }) => <li className="text-sm">{children}</li>,
                    strong: ({ children }) => <strong className="font-semibold text-text">{children}</strong>,
                    code: ({ children }) => (
                      <code className="px-1 py-0.5 rounded text-xs bg-black/20 font-mono">{children}</code>
                    ),
                    pre: ({ children }) => (
                      <pre className="mt-1 mb-2 p-2 rounded-lg bg-black/20 text-xs font-mono overflow-x-auto max-h-[200px] overflow-y-auto">{children}</pre>
                    ),
                    table: ({ children }) => (
                      <div className="overflow-x-auto my-2 -mx-1">
                        <table className="text-xs border-collapse min-w-full">{children}</table>
                      </div>
                    ),
                    th: ({ children }) => (
                      <th className="px-2 py-1 border border-border/30 text-left font-semibold bg-black/10">{children}</th>
                    ),
                    td: ({ children }) => (
                      <td className="px-2 py-1 border border-border/30">{children}</td>
                    ),
                  }}
                >
                  {msg.content}
                </ReactMarkdown>
              </div>
              {/* Gradient overlay + expand button */}
              {isOverflowing && !expanded && (
                <div className="absolute bottom-0 left-0 right-0 h-16 bg-gradient-to-t from-[var(--color-assistant-raised)] to-transparent flex items-end justify-center pb-1">
                  <button
                    onClick={() => setExpanded(true)}
                    className="flex items-center gap-1 text-[11px] text-accent hover:text-accent-hover font-medium"
                  >
                    <ChevronDown className="w-3.5 h-3.5" />
                    Mehr anzeigen
                  </button>
                </div>
              )}
              {isOverflowing && expanded && (
                <button
                  onClick={() => setExpanded(false)}
                  className="flex items-center gap-1 text-[11px] text-accent hover:text-accent-hover font-medium mt-1"
                >
                  <ChevronUp className="w-3.5 h-3.5" />
                  Weniger anzeigen
                </button>
              )}
            </div>
          )}
        </div>

        {/* Pending tool proposals — hard-gate: backend only executes after explicit confirm click */}
        {!isUser && msg.pendingTools && msg.pendingTools.length > 0 && (
          <div className="flex flex-col gap-1.5 mt-0.5 w-full">
            {msg.pendingTools.map((tool) => {
              const existingResult = msg.toolResults?.find((r) => r.toolId === tool.id);
              return (
                <PendingToolCard key={tool.id} tool={tool} sessionId={sessionId} existingResult={existingResult} />
              );
            })}
          </div>
        )}

        {/* Actions -- filter out run_workflow/show_workflow/show_run with missing id */}
        {msg.actions && msg.actions.length > 0 && (() => {
          const visible = visibleActions(msg.actions);
          return visible.length > 0 ? (
            <div className="flex flex-wrap gap-1.5 mt-0.5">
              {visible.map((action, i) => (
                <ActionButton key={i} action={action} onClose={onClose} onSend={onSend} onExecute={onExecute} />
              ))}
            </div>
          ) : null;
        })()}

        {/* Error retry button */}
        {msg.isError && onRetry && (
          <button
            onClick={onRetry}
            className="flex items-center gap-1 text-[11px] text-danger hover:text-danger/80 font-medium mt-0.5"
          >
            <RefreshCw className="w-3 h-3" />
            Erneut versuchen
          </button>
        )}

        {/* Timestamp + response time + provider + status + copy */}
        <span className="text-[10px] text-text-tertiary ml-1 flex items-center gap-1">
          {time}
          {!isUser && respTime && (
            <>{time ? ' · ' : ''}{respTime}</>
          )}
          {!isUser && msg.provider && (
            <>{(time || respTime) ? ' · ' : ''}{msg.provider === 'CODEX' ? 'Codex' : 'Claude'}</>
          )}
          {isUser && msg.status === 'sent' && <CheckCheck className="w-2.5 h-2.5 text-accent" />}
          {isUser && msg.status === 'error' && <XCircle className="w-2.5 h-2.5 text-danger" />}
          <CopyButton text={msg.content} className="p-0 rounded-sm text-text-tertiary hover:text-accent transition-colors opacity-0 group-hover/msg:opacity-100" />
        </span>
      </div>
    </div>
  );
}
