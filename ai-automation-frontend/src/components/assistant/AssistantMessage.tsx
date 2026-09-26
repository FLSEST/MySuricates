import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronDown, ChevronUp, RefreshCw, CheckCheck, XCircle } from 'lucide-react';
import { CopyButton } from '../shared/CopyButton';
import { MeerkatIcon } from '../layout/MeerkatIcon';
import { formatAssistantTime, formatAssistantResponseTime, visibleActions } from '../assistantPanelUtils';
import { useI18nStore } from '../../i18n';
import type { ChatMessage } from '../../hooks/useAssistant';
import { ActionButton } from './ActionButton';
import { AssistantMarkdown } from './AssistantMarkdown';
import { PendingToolCard } from './PendingToolCard';

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
                <AssistantMarkdown content={msg.content} onNavigate={handleNavigate} />
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
