import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { X, Send, Play, Eye, Sparkles, Clock, Plus, Trash2, ArrowLeft } from 'lucide-react';
import { useAssistantStore } from '../stores/assistantStore';
import { useAssistantChat, useAssistantSuggestions, type ChatMessage, type AssistantAction } from '../hooks/useAssistant';
import { useAssistantThinkingPhrases } from '../hooks/useSystem';
import { MeerkatIcon } from './layout/Sidebar';

function parsePageContext(pathname: string) {
  const segments = pathname.replace(/^\//, '').split('/');

  if (segments[0] === 'workflows' && segments[2] === 'run') {
    return { page: 'run', entityId: segments[3], workflowId: segments[1] };
  }
  if (segments[0] === 'workflows' && segments[1]) {
    return { page: 'workflow', entityId: segments[1] };
  }
  if (segments[0] === 'projects' && segments[1]) {
    return { page: 'project', entityId: segments[1], projectId: segments[1] };
  }
  return { page: segments[0] || 'dashboard' };
}

function ActionButton({ action, onClose }: { action: AssistantAction; onClose: () => void }) {
  const navigate = useNavigate();

  const icon = action.type === 'run_workflow' ? <Play className="w-3 h-3" />
    : action.type === 'show_workflow' || action.type === 'show_run' ? <Eye className="w-3 h-3" />
    : <Sparkles className="w-3 h-3" />;

  const handleClick = () => {
    switch (action.type) {
      case 'run_workflow':
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

function MarkdownLink({ href, children, onNavigate }: {
  href?: string;
  children?: React.ReactNode;
  onNavigate: (path: string) => void;
}) {
  if (href?.startsWith('/')) {
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

function MessageBubble({ msg, onClose }: { msg: ChatMessage; onClose: () => void }) {
  const navigate = useNavigate();
  const isUser = msg.role === 'user';

  const handleNavigate = (path: string) => {
    navigate(path);
    onClose();
  };

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

      <div className={`flex flex-col gap-1 max-w-[85%] ${isUser ? 'items-end' : 'items-start'}`}>
        {/* Bubble */}
        <div className={`px-3 py-2 rounded-2xl text-sm leading-relaxed
                         ${isUser
                           ? 'bg-accent text-white rounded-tr-sm whitespace-pre-wrap'
                           : 'bg-[var(--color-assistant-raised)] border border-[var(--color-assistant-border)] text-text rounded-tl-sm'
                         }`}>
          {isUser ? (
            msg.content
          ) : (
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
                  <pre className="mt-1 mb-2 p-2 rounded-lg bg-black/20 text-xs font-mono overflow-x-auto">{children}</pre>
                ),
              }}
            >
              {msg.content}
            </ReactMarkdown>
          )}
        </div>

        {/* Actions */}
        {msg.actions && msg.actions.length > 0 && (
          <div className="flex flex-wrap gap-1.5 mt-0.5">
            {msg.actions.map((action, i) => (
              <ActionButton key={i} action={action} onClose={onClose} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

const DEFAULT_PHRASES = ['Denke nach…', 'Analysiere…', 'Überlege kurz…', 'Einen Moment…'];

function TypingIndicator() {
  const { data: phrases = DEFAULT_PHRASES } = useAssistantThinkingPhrases();
  const [idx, setIdx] = useState(() => Math.floor(Math.random() * Math.max(phrases.length, 1)));

  useEffect(() => {
    const t = setInterval(() => setIdx((i) => (i + 1) % phrases.length), 2500);
    return () => clearInterval(t);
  }, [phrases.length]);

  const phrase = phrases[idx % phrases.length] ?? phrases[0] ?? '…';

  return (
    <div className="flex gap-2 items-start">
      <div className="shrink-0 w-7 h-7 rounded-full bg-[var(--color-assistant-raised)]
                      border border-[var(--color-assistant-border)] flex items-center justify-center mt-0.5">
        <MeerkatIcon className="text-accent" size={18} />
      </div>
      <div className="px-3 py-2.5 rounded-2xl rounded-tl-sm bg-[var(--color-assistant-raised)]
                      border border-[var(--color-assistant-border)]">
        <span className="text-sm text-text-secondary animate-pulse">{phrase}</span>
      </div>
    </div>
  );
}

function HistoryView({
  onBack,
  onNewSession,
}: {
  onBack: () => void;
  onNewSession: () => void;
}) {
  const { sessions, loadSession, deleteSession } = useAssistantStore();

  const formatDate = (ts: number) => {
    const d = new Date(ts);
    const today = new Date();
    const isToday = d.toDateString() === today.toDateString();
    if (isToday) return d.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
    return d.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' }) +
      ' ' + d.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  };

  return (
    <div className="flex flex-col flex-1 min-h-0">
      <div className="flex items-center gap-2 px-4 py-3 border-b border-[var(--color-assistant-border)]">
        <button
          onClick={onBack}
          className="p-1 rounded-lg text-text-tertiary hover:text-text hover:bg-[var(--color-assistant-raised)] transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
        </button>
        <span className="text-sm font-medium text-text">Bisherige Gespräche</span>
      </div>

      <div className="flex-1 overflow-y-auto px-3 py-3 space-y-1.5 min-h-0">
        {sessions.length === 0 ? (
          <div className="text-center py-8 text-text-tertiary text-sm">Noch keine Gespräche</div>
        ) : (
          sessions.map((sess) => (
            <div
              key={sess.id}
              className="group flex items-center gap-2 px-3 py-2.5 rounded-xl cursor-pointer
                         hover:bg-[var(--color-assistant-raised)] transition-colors border border-transparent
                         hover:border-[var(--color-assistant-border)]"
              onClick={() => loadSession(sess.id)}
            >
              <div className="flex-1 min-w-0">
                <p className="text-sm text-text truncate">
                  {sess.preview || 'Leere Sitzung'}
                </p>
                <p className="text-xs text-text-tertiary mt-0.5">{formatDate(sess.startedAt)}</p>
              </div>
              <button
                onClick={(e) => { e.stopPropagation(); deleteSession(sess.id); }}
                className="shrink-0 opacity-0 group-hover:opacity-100 p-1 rounded-lg
                           text-text-tertiary hover:text-danger transition-all"
              >
                <Trash2 className="w-3.5 h-3.5" />
              </button>
            </div>
          ))
        )}
      </div>

      <div className="px-3 py-3 border-t border-[var(--color-assistant-border)]">
        <button
          onClick={onNewSession}
          className="w-full flex items-center justify-center gap-2 py-2 rounded-xl text-sm
                     text-text-secondary border border-[var(--color-assistant-border)]
                     hover:border-accent/40 hover:text-accent hover:bg-accent/5 transition-colors"
        >
          <Plus className="w-4 h-4" />
          Neues Gespräch
        </button>
      </div>
    </div>
  );
}

function EmptyState({ page, onSuggestionClick }: { page: string; onSuggestionClick: (text: string) => void }) {
  const { data: suggestions } = useAssistantSuggestions(page);
  const items = suggestions?.length
    ? suggestions
    : ['Was lief heute?', 'Gibt es Probleme?', 'Was steht an?'];

  return (
    <div className="flex flex-col items-center justify-center h-full gap-4 text-center">
      <div className="animate-pulse-subtle">
        <MeerkatIcon className="text-accent" size={52} />
      </div>
      <div>
        <p className="text-sm font-semibold text-text">Hallo! Ich bin MySuricates.</p>
        <p className="text-xs text-text-secondary mt-1">
          Frag mich alles über deine Automatisierungen.
        </p>
      </div>
      <div className="flex flex-col gap-2 w-full mt-1">
        {items.map((s) => (
          <button
            key={s}
            onClick={() => onSuggestionClick(s)}
            className="text-left px-3 py-2 rounded-xl text-xs text-text-secondary
                       border border-[var(--color-assistant-border)]
                       hover:border-accent/40 hover:text-text
                       hover:bg-[var(--color-assistant)] transition-colors"
          >
            {s}
          </button>
        ))}
      </div>
    </div>
  );
}

function ContextChips({ page, onChipClick }: { page: string; onChipClick: (text: string) => void }) {
  const { data: suggestions } = useAssistantSuggestions(page);
  if (!suggestions?.length) return null;
  return (
    <div
      className="flex gap-2 px-4 py-2 overflow-x-auto shrink-0 scrollbar-none"
      style={{ borderBottom: '1px solid var(--color-assistant-border)' }}
    >
      {suggestions.map((s) => (
        <button
          key={s}
          onClick={() => onChipClick(s)}
          className="shrink-0 px-3 py-1 rounded-full text-xs font-medium whitespace-nowrap
                     bg-accent/10 text-accent hover:bg-accent/20 border border-accent/20 transition-colors"
        >
          {s}
        </button>
      ))}
    </div>
  );
}

export function AssistantPanel() {
  const {
    open, toggle, setOpen,
    sessions, activeSessionId, showHistory, panelWidth, panelHeight,
    pendingInput, setPendingInput,
    startNewSession, appendMessage,
    setShowHistory, setPanelWidth, setPanelHeight,
  } = useAssistantStore();

  const location = useLocation();
  const [input, setInput] = useState('');
  const [lastModel, setLastModel] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const { mutate: sendMessage, isPending } = useAssistantChat();

  const ctx = parsePageContext(location.pathname);

  // Resize drag state
  const [isResizing, setIsResizing] = useState(false);
  const dragInfo = useRef<{ startX: number; startWidth: number } | null>(null);
  const heightDragInfo = useRef<{ startY: number; startHeight: number } | null>(null);

  const activeSession = sessions.find((s) => s.id === activeSessionId) ?? null;
  const messages = activeSession?.messages ?? [];

  // Scroll to bottom on new messages
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isPending]);

  // Focus input when opening
  useEffect(() => {
    if (open && !showHistory) {
      setTimeout(() => inputRef.current?.focus(), 100);
    }
  }, [open, showHistory]);

  // Consume pendingInput from store (set by Header SuggestionButton)
  useEffect(() => {
    if (open && pendingInput) {
      setInput(pendingInput);
      setPendingInput(null);
      setTimeout(() => inputRef.current?.focus(), 50);
    }
  }, [open, pendingInput, setPendingInput]);

  // ESC to close
  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [setOpen]);

  // Resize via pointer capture — browser keeps the pointer locked to the element
  const onResizePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    dragInfo.current = { startX: e.clientX, startWidth: panelWidth };
    setIsResizing(true);
    e.preventDefault();
  };

  const onResizePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragInfo.current) return;
    const delta = dragInfo.current.startX - e.clientX;
    const maxWidth = window.innerWidth - 80; // 80px = sm:right-20 offset
    const newWidth = Math.min(maxWidth, Math.max(280, dragInfo.current.startWidth + delta));
    setPanelWidth(newWidth);
  };

  const onResizePointerUp = () => {
    dragInfo.current = null;
    setIsResizing(false);
  };

  // Height resize (top edge)
  const onHeightPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    heightDragInfo.current = { startY: e.clientY, startHeight: panelHeight };
    setIsResizing(true);
    e.preventDefault();
  };

  const onHeightPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!heightDragInfo.current) return;
    const delta = heightDragInfo.current.startY - e.clientY; // drag up = taller
    const maxHeight = window.innerHeight - 60;
    const newHeight = Math.min(maxHeight, Math.max(300, heightDragInfo.current.startHeight + delta));
    setPanelHeight(newHeight);
  };

  const onHeightPointerUp = () => {
    heightDragInfo.current = null;
    setIsResizing(false);
  };

  const handleSend = () => {
    const text = input.trim();
    if (!text || isPending) return;

    // Get or create session
    let sessionId = activeSessionId;
    if (!sessionId || !sessions.find((s) => s.id === sessionId)) {
      sessionId = startNewSession();
    }

    const userMsg: ChatMessage = { id: crypto.randomUUID(), role: 'user', content: text };
    appendMessage(sessionId, userMsg);
    setInput('');

    const currentSession = useAssistantStore.getState().sessions.find((s) => s.id === sessionId);
    const history = (currentSession?.messages ?? [])
      .filter((m) => m.id !== userMsg.id)
      .map((m) => ({ role: m.role, content: m.content }));

    sendMessage(
      { message: text, history, context: ctx, sessionId },
      {
        onSuccess: (res) => {
          if (res.model) setLastModel(res.model);
          appendMessage(sessionId!, {
            id: crypto.randomUUID(),
            role: 'assistant',
            content: res.message,
            actions: res.actions,
          });
        },
        onError: () => {
          appendMessage(sessionId!, {
            id: crypto.randomUUID(),
            role: 'assistant',
            content: 'Entschuldigung, ein Fehler ist aufgetreten. Bitte versuche es erneut.',
          });
        },
      }
    );
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleNewSession = () => {
    startNewSession();
    setShowHistory(false);
  };

  const isMobile = window.innerWidth < 640;
  const effectiveWidth = isMobile ? undefined : panelWidth;

  return (
    <>
      {/* Floating button */}
      <button
        onClick={toggle}
        title="MySuricates Assistent"
        className={`fixed bottom-5 right-5 z-50 w-12 h-12 rounded-full shadow-lg
                    flex items-center justify-center transition-all duration-200
                    ${open
                      ? 'bg-[var(--color-assistant-raised)] border border-[var(--color-assistant-border)] text-text-secondary'
                      : 'bg-accent text-white hover:scale-110 hover:shadow-accent/30 hover:shadow-xl'
                    }`}
      >
        {open
          ? <X className="w-5 h-5" />
          : <MeerkatIcon className="text-white" size={22} />
        }
      </button>

      {/* Panel */}
      <div
        className={`fixed bottom-0 right-0 z-50 flex flex-col
                    w-full sm:bottom-5 sm:right-20
                    rounded-t-2xl sm:rounded-2xl shadow-2xl overflow-hidden
                    ${open ? 'translate-y-0 opacity-100' : 'translate-y-4 opacity-0 pointer-events-none'}`}
        style={{
          width: effectiveWidth,
          height: isMobile ? '85vh' : panelHeight,
          backgroundColor: 'var(--color-assistant)',
          borderColor: 'var(--color-assistant-border)',
          borderWidth: 1,
          borderStyle: 'solid',
          transition: isResizing ? 'opacity 0.3s ease-out' : 'transform 0.3s ease-out, opacity 0.3s ease-out',
        }}
      >
        {/* Width resize handle — left edge (desktop only) */}
        {!isMobile && (
          <div
            onPointerDown={onResizePointerDown}
            onPointerMove={onResizePointerMove}
            onPointerUp={onResizePointerUp}
            className={`absolute left-0 top-0 bottom-0 w-2 cursor-ew-resize z-10 transition-colors
                        ${isResizing ? 'bg-accent/60' : 'bg-transparent hover:bg-accent/30'}`}
            title="Breite anpassen"
          />
        )}

        {/* Height resize handle — top edge (desktop only) */}
        {!isMobile && (
          <div
            onPointerDown={onHeightPointerDown}
            onPointerMove={onHeightPointerMove}
            onPointerUp={onHeightPointerUp}
            className={`absolute left-0 right-0 top-0 h-2 cursor-ns-resize z-10 transition-colors
                        ${isResizing ? 'bg-accent/60' : 'bg-transparent hover:bg-accent/30'}`}
            title="Höhe anpassen"
          />
        )}

        {/* Header */}
        <div
          className="flex items-center gap-3 px-4 py-3 shrink-0"
          style={{ borderBottom: '1px solid var(--color-assistant-border)' }}
        >
          <MeerkatIcon className="text-accent" size={28} />
          <div className="flex-1 min-w-0">
            <p className="text-sm font-semibold text-text leading-none">MySuricates</p>
            <p className="text-xs text-text-tertiary mt-0.5">
              {lastModel
                ? lastModel.replace('claude-', '').replace('-20251001', '')
                : 'Online'}
            </p>
          </div>
          <div className="flex items-center gap-1">
            <button
              onClick={() => setShowHistory(!showHistory)}
              title="Gesprächsverlauf"
              className={`p-1.5 rounded-lg transition-colors
                          ${showHistory
                            ? 'text-accent bg-accent/10'
                            : 'text-text-tertiary hover:text-text hover:bg-[var(--color-assistant-raised)]'
                          }`}
            >
              <Clock className="w-4 h-4" />
            </button>
            <button
              onClick={handleNewSession}
              title="Neues Gespräch"
              className="p-1.5 rounded-lg text-text-tertiary hover:text-text hover:bg-[var(--color-assistant-raised)] transition-colors"
            >
              <Plus className="w-4 h-4" />
            </button>
            <button
              onClick={() => setOpen(false)}
              className="p-1.5 rounded-lg text-text-tertiary hover:text-text hover:bg-[var(--color-assistant-raised)] transition-colors"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        </div>

        {showHistory ? (
          <HistoryView
            onBack={() => setShowHistory(false)}
            onNewSession={handleNewSession}
          />
        ) : (
          <>
            {/* Context chips — only when conversation has started */}
            {messages.length > 0 && (
              <ContextChips page={ctx.page ?? 'dashboard'} onChipClick={setInput} />
            )}

            {/* Messages */}
            <div
              className="flex-1 overflow-y-auto px-4 py-4 space-y-4 min-h-0"
              style={{ backgroundColor: 'var(--color-assistant-raised)' }}
            >
              {messages.length === 0 && (
                <EmptyState page={ctx.page ?? 'dashboard'} onSuggestionClick={setInput} />
              )}

              {messages.map((msg) => (
                <MessageBubble key={msg.id} msg={msg} onClose={() => setOpen(false)} />
              ))}

              {isPending && <TypingIndicator />}

              <div ref={bottomRef} />
            </div>

            {/* Input */}
            <div
              className="px-3 py-3 shrink-0"
              style={{ borderTop: '1px solid var(--color-assistant-border)' }}
            >
              <div
                className="flex items-end gap-2 rounded-xl px-3 py-2
                           focus-within:ring-1 focus-within:ring-accent/40 transition-all"
                style={{
                  backgroundColor: 'var(--color-assistant)',
                  border: '1px solid var(--color-assistant-border)',
                }}
              >
                <textarea
                  ref={inputRef}
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder="Nachricht eingeben… (Enter zum Senden)"
                  rows={1}
                  disabled={isPending}
                  className="flex-1 resize-none bg-transparent text-sm text-text placeholder-text-tertiary
                             outline-none min-h-[24px] max-h-[120px] leading-6 py-0"
                  style={{ height: 'auto' }}
                  onInput={(e) => {
                    const el = e.currentTarget;
                    el.style.height = 'auto';
                    el.style.height = Math.min(el.scrollHeight, 120) + 'px';
                  }}
                />
                <button
                  onClick={handleSend}
                  disabled={!input.trim() || isPending}
                  className="shrink-0 w-7 h-7 rounded-lg bg-accent text-white flex items-center justify-center
                             disabled:opacity-40 hover:bg-accent-hover transition-colors"
                >
                  <Send className="w-3.5 h-3.5" />
                </button>
              </div>
              <p className="text-[10px] text-text-tertiary text-center mt-1.5">
                Shift+Enter für Zeilenumbruch
              </p>
            </div>
          </>
        )}
      </div>
    </>
  );
}
