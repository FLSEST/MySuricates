import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { ChatMessage, AssistantPageContext, ToolExecutionResult } from '../hooks/useAssistant';
import { generateId } from '../utils/uuid';

export interface ChatSession {
  id: string;
  startedAt: number;
  preview: string;
  messages: ChatMessage[];
}

export interface AssistantInFlight {
  sessionId: string;
  requestId: string;
  /** The user message this request answers. */
  userMsgId: string;
}

interface AssistantState {
  open: boolean;
  sessions: ChatSession[];
  activeSessionId: string | null;
  showHistory: boolean;
  panelWidth: number;
  panelHeight: number;
  pendingInput: string | null;
  pendingContext: AssistantPageContext | null;
  /**
   * The request on its way to the assistant (transient, not persisted) — one at a time for panel and
   * Simple home alike; see `useAssistantConversation`.
   */
  inFlight: AssistantInFlight | null;
  /** Model of the latest answer (transient), shown in the panel header. */
  lastModel: string | null;

  toggle: () => void;
  setOpen: (open: boolean) => void;
  setShowHistory: (v: boolean) => void;
  setPanelWidth: (w: number) => void;
  setPanelHeight: (h: number) => void;
  setPendingInput: (text: string | null) => void;
  setPendingContext: (ctx: AssistantPageContext | null) => void;
  setInFlight: (inFlight: AssistantInFlight | null) => void;
  setLastModel: (model: string | null) => void;

  startNewSession: () => string;
  appendMessage: (sessionId: string, msg: ChatMessage) => void;
  updateMessageStatus: (sessionId: string, msgId: string, status: ChatMessage['status']) => void;
  /**
   * Keeps a tool decision's result on the message that proposed the tool (the one whose `pendingTools`
   * contain `result.toolId`) — so the card shows it again and the next turn sends it along (TODO 2f7766e7).
   */
  recordToolResult: (sessionId: string, result: ToolExecutionResult) => void;
  deleteSession: (id: string) => void;
  loadSession: (id: string) => void;
  hydrateSession: (session: ChatSession) => void;
}

const MAX_SESSIONS = 20;

export const useAssistantStore = create<AssistantState>()(
  persist(
    (set) => ({
      open: false,
      sessions: [],
      activeSessionId: null,
      showHistory: false,
      panelWidth: 420,
      panelHeight: 600,
      pendingInput: null,
      pendingContext: null,
      inFlight: null,
      lastModel: null,

      toggle: () => set((s) => ({ open: !s.open })),
      setOpen: (open) => set({ open }),
      setShowHistory: (showHistory) => set({ showHistory }),
      setPanelWidth: (panelWidth) => set({ panelWidth }),
      setPanelHeight: (panelHeight) => set({ panelHeight }),
      setPendingInput: (pendingInput) => set({ pendingInput }),
      setPendingContext: (pendingContext) => set({ pendingContext }),
      setInFlight: (inFlight) => set({ inFlight }),
      setLastModel: (lastModel) => set({ lastModel }),

      startNewSession: () => {
        const id = generateId();
        const session: ChatSession = {
          id,
          startedAt: Date.now(),
          preview: '',
          messages: [],
        };
        set((s) => ({
          sessions: [session, ...s.sessions].slice(0, MAX_SESSIONS),
          activeSessionId: id,
        }));
        return id;
      },

      appendMessage: (sessionId, msg) => {
        set((s) => ({
          sessions: s.sessions.map((sess) => {
            if (sess.id !== sessionId) return sess;
            const messages = [...sess.messages, msg];
            const firstUser = messages.find((m) => m.role === 'user');
            const preview = firstUser
              ? firstUser.content.slice(0, 60) + (firstUser.content.length > 60 ? '…' : '')
              : sess.preview;
            return { ...sess, messages, preview };
          }),
        }));
      },

      updateMessageStatus: (sessionId, msgId, status) => {
        set((s) => ({
          sessions: s.sessions.map((sess) => {
            if (sess.id !== sessionId) return sess;
            return {
              ...sess,
              messages: sess.messages.map((m) =>
                m.id === msgId ? { ...m, status } : m
              ),
            };
          }),
        }));
      },

      recordToolResult: (sessionId, result) => {
        set((s) => ({
          sessions: s.sessions.map((sess) => {
            if (sess.id !== sessionId) return sess;
            return {
              ...sess,
              messages: sess.messages.map((m) => {
                if (!m.pendingTools?.some((p) => p.id === result.toolId)) return m;
                const others = (m.toolResults ?? []).filter((r) => r.toolId !== result.toolId);
                return { ...m, toolResults: [...others, result] };
              }),
            };
          }),
        }));
      },

      deleteSession: (id) => {
        set((s) => {
          const sessions = s.sessions.filter((sess) => sess.id !== id);
          const activeSessionId = s.activeSessionId === id
            ? (sessions[0]?.id ?? null)
            : s.activeSessionId;
          return { sessions, activeSessionId };
        });
      },

      loadSession: (id) => {
        set({ activeSessionId: id, showHistory: false });
      },

      hydrateSession: (session) => {
        set((s) => {
          if (s.sessions.find((sess) => sess.id === session.id)) return {};
          return {
            sessions: [session, ...s.sessions].slice(0, MAX_SESSIONS),
          };
        });
      },
    }),
    {
      name: 'assistant-sessions',
      partialize: (state) => ({
        sessions: state.sessions,
        activeSessionId: state.activeSessionId,
        panelWidth: state.panelWidth,
        panelHeight: state.panelHeight,
      }),
    }
  )
);
