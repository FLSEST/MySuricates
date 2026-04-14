import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { ChatMessage } from '../hooks/useAssistant';
import { generateId } from '../utils/uuid';

export interface ChatSession {
  id: string;
  startedAt: number;
  preview: string;
  messages: ChatMessage[];
}

interface AssistantState {
  open: boolean;
  sessions: ChatSession[];
  activeSessionId: string | null;
  showHistory: boolean;
  panelWidth: number;
  panelHeight: number;
  pendingInput: string | null;

  toggle: () => void;
  setOpen: (open: boolean) => void;
  setShowHistory: (v: boolean) => void;
  setPanelWidth: (w: number) => void;
  setPanelHeight: (h: number) => void;
  setPendingInput: (text: string | null) => void;

  startNewSession: () => string;
  appendMessage: (sessionId: string, msg: ChatMessage) => void;
  updateMessageStatus: (sessionId: string, msgId: string, status: ChatMessage['status']) => void;
  deleteSession: (id: string) => void;
  loadSession: (id: string) => void;
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

      toggle: () => set((s) => ({ open: !s.open })),
      setOpen: (open) => set({ open }),
      setShowHistory: (showHistory) => set({ showHistory }),
      setPanelWidth: (panelWidth) => set({ panelWidth }),
      setPanelHeight: (panelHeight) => set({ panelHeight }),
      setPendingInput: (pendingInput) => set({ pendingInput }),

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
