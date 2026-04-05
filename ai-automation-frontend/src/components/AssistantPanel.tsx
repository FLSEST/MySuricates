import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { X, Send, Clock, Plus, Square } from 'lucide-react';
import { useAssistantStore } from '../stores/assistantStore';
import { useAssistantChat, type ChatMessage } from '../hooks/useAssistant';
import { useAssistantWebSocket } from '../hooks/useAssistantWebSocket';
import { useExecuteWorkflow } from '../hooks/useWorkflows';
import { MeerkatIcon } from './layout/Sidebar';
import { parsePageContext } from './assistantPanelUtils';
import { MessageBubble } from './assistant/AssistantMessage';
import { AssistantHistory } from './assistant/AssistantHistory';
import { StatusIndicator, AssistantEmptyState, ContextChips } from './assistant/AssistantWelcome';

export function AssistantPanel() {
  const {
    open, toggle, setOpen,
    sessions, activeSessionId, showHistory, panelWidth, panelHeight,
    pendingInput, setPendingInput,
    startNewSession, appendMessage,
    setShowHistory, setPanelWidth, setPanelHeight,
  } = useAssistantStore();
  const updateMessageStatus = useAssistantStore((s) => s.updateMessageStatus);

  const location = useLocation();
  const [input, setInput] = useState('');
  const [lastModel, setLastModel] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const { mutate: sendMessage, isPending } = useAssistantChat();
  const { mutate: executeWorkflow } = useExecuteWorkflow();
  const navigate = useNavigate();
  const abortRef = useRef<AbortController | null>(null);
  const queueRef = useRef<{ text: string; sessionId: string }[]>([]);
  const [activeRequestId, setActiveRequestId] = useState<string | null>(null);
  const { currentPhase } = useAssistantWebSocket(activeRequestId, activeSessionId);

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

  const dispatchChat = (text: string, sessionId: string, userMsgId: string) => {
    const currentSession = useAssistantStore.getState().sessions.find((s) => s.id === sessionId);
    const history = (currentSession?.messages ?? [])
      .filter((m) => m.id !== userMsgId && m.role !== 'assistant' || m.role === 'assistant')
      .filter((m) => m.id !== userMsgId)
      .map((m) => ({ role: m.role, content: m.content }));

    const controller = new AbortController();
    abortRef.current = controller;
    const requestId = crypto.randomUUID();
    setActiveRequestId(requestId);

    sendMessage(
      { req: { message: text, history, context: ctx, sessionId, requestId }, signal: controller.signal },
      {
        onSuccess: (res) => {
          abortRef.current = null;
          setActiveRequestId(null);
          updateMessageStatus(sessionId, userMsgId, 'sent');
          if (res.model) setLastModel(res.model);
          appendMessage(sessionId, {
            id: crypto.randomUUID(),
            role: 'assistant',
            content: res.message,
            actions: res.actions,
            responseTimeMs: res.responseTimeMs ?? undefined,
            timestamp: Date.now(),
          });
          processQueue();
        },
        onError: (err) => {
          abortRef.current = null;
          setActiveRequestId(null);
          // AbortError = user cancelled, don't show error bubble
          const isAbort = err instanceof DOMException && err.name === 'AbortError';
          if (isAbort) {
            updateMessageStatus(sessionId, userMsgId, undefined as unknown as 'sending');
            // Clear status silently — message stays but no error shown
            processQueue();
            return;
          }
          updateMessageStatus(sessionId, userMsgId, 'error');
          appendMessage(sessionId, {
            id: crypto.randomUUID(),
            role: 'assistant',
            content: 'Entschuldigung, ein Fehler ist aufgetreten. Bitte versuche es erneut.',
            timestamp: Date.now(),
            isError: true,
          });
          processQueue();
        },
      }
    );
  };

  const processQueue = () => {
    const next = queueRef.current.shift();
    if (next) {
      const now = +new Date();
      const userMsg: ChatMessage = { id: crypto.randomUUID(), role: 'user', content: next.text, timestamp: now, status: 'sending' };
      appendMessage(next.sessionId, userMsg);
      dispatchChat(next.text, next.sessionId, userMsg.id);
    }
  };

  const handleSendText = (text: string) => {
    if (!text) return;

    // Get or create session
    let sessionId = activeSessionId;
    if (!sessionId || !sessions.find((s) => s.id === sessionId)) {
      sessionId = startNewSession();
    }

    const now = +new Date();
    const userMsg: ChatMessage = { id: crypto.randomUUID(), role: 'user', content: text, timestamp: now, status: 'sending' };
    appendMessage(sessionId, userMsg);

    if (isPending) {
      // Queue for later — message is already visible with ⏳
      queueRef.current.push({ text, sessionId });
      return;
    }

    dispatchChat(text, sessionId, userMsg.id);
  };

  const handleCancel = () => {
    abortRef.current?.abort();
    abortRef.current = null;
    setActiveRequestId(null);
  };

  const handleSend = () => {
    const text = input.trim();
    if (!text) return;
    setInput('');
    handleSendText(text);
  };

  const handleExecute = (workflowId: string) => {
    executeWorkflow({ id: workflowId }, {
      onSuccess: (run) => {
        navigate(`/workflows/${workflowId}/run/${run.id}`);
        setOpen(false);
      },
    });
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
          <AssistantHistory
            onBack={() => setShowHistory(false)}
            onNewSession={handleNewSession}
          />
        ) : (
          <>
            {/* Context chips — only when conversation has started */}
            {messages.length > 0 && (
              <ContextChips page={ctx.page ?? 'dashboard'} onChipClick={setInput} inputRef={inputRef} />
            )}

            {/* Messages */}
            <div
              className="flex-1 overflow-y-auto px-4 py-4 space-y-4 min-h-0"
              style={{ backgroundColor: 'var(--color-assistant-raised)' }}
            >
              {messages.length === 0 && (
                <AssistantEmptyState page={ctx.page ?? 'dashboard'} onSuggestionClick={setInput} inputRef={inputRef} />
              )}

              {messages.map((msg, idx) => (
                <MessageBubble
                  key={msg.id}
                  msg={msg}
                  onClose={() => setOpen(false)}
                  onSend={handleSendText}
                  onExecute={handleExecute}
                  onRetry={msg.isError ? () => {
                    const lastUserMsg = messages.slice(0, idx).reverse().find(m => m.role === 'user');
                    if (lastUserMsg) handleSendText(lastUserMsg.content);
                  } : undefined}
                />
              ))}

              {isPending && (
                <div className="flex items-center gap-2">
                  <StatusIndicator phase={currentPhase} />
                  <button
                    onClick={handleCancel}
                    className="shrink-0 flex items-center gap-1 text-[11px] text-text-tertiary hover:text-text-secondary"
                  >
                    <Square className="w-3 h-3" />
                    Abbrechen
                  </button>
                </div>
              )}

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
                  disabled={!input.trim()}
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
