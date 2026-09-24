import { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { X, Clock, Plus, Square } from 'lucide-react';
import { useAssistantStore } from '../stores/assistantStore';
import { useAssistantConversation } from '../hooks/useAssistantConversation';
import { useAssistantWebSocket } from '../hooks/useAssistantWebSocket';
import { useExecuteWorkflow } from '../hooks/useWorkflows';
import { usePanelResize } from '../hooks/usePanelResize';
import { MeerkatIcon } from './layout/Sidebar';
import { parsePageContext } from './assistantPanelUtils';
import { MessageBubble } from './assistant/AssistantMessage';
import { AssistantHistory } from './assistant/AssistantHistory';
import { AssistantComposer } from './assistant/AssistantComposer';
import { StatusIndicator, AssistantEmptyState, ContextChips } from './assistant/AssistantWelcome';
import { useT } from '../i18n';

export function AssistantPanel() {
  const {
    open, toggle, setOpen,
    sessions, activeSessionId, showHistory, panelWidth, panelHeight,
    pendingInput, setPendingInput,
    pendingContext, setPendingContext,
    startNewSession,
    setShowHistory, setPanelWidth, setPanelHeight,
    lastModel,
  } = useAssistantStore();
  const t = useT();

  const location = useLocation();
  const [input, setInput] = useState('');
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  // Sending, queueing and cancelling are shared with the Simple home's field
  const { send, cancel, isPending, inFlight } = useAssistantConversation();
  const { mutate: executeWorkflow } = useExecuteWorkflow();
  const navigate = useNavigate();
  const { currentPhase } = useAssistantWebSocket(inFlight?.requestId ?? null, activeSessionId);

  const ctx = pendingContext ?? parsePageContext(location.pathname);

  // Resize via the left and top edge (desktop only). 80px = sm:right-20 offset of the panel.
  const { isResizing, widthHandle, heightHandle } = usePanelResize({
    width: { size: panelWidth, onChange: setPanelWidth, min: 280, margin: 80 },
    height: { size: panelHeight, onChange: setPanelHeight, min: 300, margin: 60 },
  });

  const activeSession = sessions.find((s) => s.id === activeSessionId) ?? null;
  const messages = useMemo(() => activeSession?.messages ?? [], [activeSession]);

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

  // Consume pendingInput from store (set by Header SuggestionButton). This is a handshake with
  // an external store: the message is copied into local state and then acknowledged
  // (setPendingInput(null)). Doing that during render would write to the Zustand store while
  // rendering, so the effect stays.
  useEffect(() => {
    if (open && pendingInput) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- one-shot handoff from the assistant store
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

  const handleSendText = (text: string) => {
    if (!text) return;

    // Get or create session
    let sessionId = activeSessionId;
    if (!sessionId || !sessions.find((s) => s.id === sessionId)) {
      sessionId = startNewSession();
    }

    // While another request is on its way the message is queued — already visible with ⏳
    send(text, sessionId, ctx);
    // Per-message context (e.g. a Diagnose run) applies once, then we revert to the URL context.
    if (pendingContext) setPendingContext(null);
  };

  const handleCancel = () => cancel();

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
        title={t.assistant.toggle}
        className={`fixed bottom-5 right-5 z-50 w-12 h-12 rounded-full shadow-lg
                    flex items-center justify-center transition-all duration-200
                    ${open
                      ? 'bg-[var(--color-assistant-raised)] border border-[var(--color-assistant-border)] text-text-secondary'
                      : 'bg-accent text-text-inverse hover:scale-110 hover:shadow-accent/30 hover:shadow-xl'
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
            {...widthHandle}
            className={`absolute left-0 top-0 bottom-0 w-2 cursor-ew-resize z-10 transition-colors
                        ${isResizing ? 'bg-accent/60' : 'bg-transparent hover:bg-accent/30'}`}
            title={t.assistant.resizeWidth}
          />
        )}

        {/* Height resize handle — top edge (desktop only) */}
        {!isMobile && (
          <div
            {...heightHandle}
            className={`absolute left-0 right-0 top-0 h-2 cursor-ns-resize z-10 transition-colors
                        ${isResizing ? 'bg-accent/60' : 'bg-transparent hover:bg-accent/30'}`}
            title={t.assistant.resizeHeight}
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
              title={t.assistant.historyToggle}
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
              title={t.assistant.newSession}
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
                  sessionId={activeSessionId ?? undefined}
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
                    {t.common.cancel}
                  </button>
                </div>
              )}

              <div ref={bottomRef} />
            </div>

            <AssistantComposer value={input} onChange={setInput} onSend={handleSend} inputRef={inputRef} />
          </>
        )}
      </div>
    </>
  );
}
