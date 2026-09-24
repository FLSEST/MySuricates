import { useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useAssistantStore } from '../../../stores/assistantStore';
import { useAssistantConversation } from '../../../hooks/useAssistantConversation';
import { parsePageContext } from '../../assistantPanelUtils';
import type { ChatMessage } from '../../../hooks/useAssistant';

export type HomeAnswerState = 'pending' | 'answered' | 'error' | 'cancelled';

export interface HomeAnswerTurn {
  sessionId: string;
  question: string;
  state: HomeAnswerState;
  /** The assistant's reply (answer or error message) once it is there. */
  reply?: ChatMessage;
  /** Request on its way for this question — for the status phase. */
  requestId: string | null;
}

/**
 * The Simple home asking the assistant in place. A question goes into an assistant session like one
 * from the panel (so "continue in chat" shows it); while the answer is shown, further questions
 * continue that session, after closing the next one starts a new session. State is derived from
 * the assistant store, so it follows whatever happens there (answer, error, cancel).
 */
export function useHomeAssistant() {
  const location = useLocation();
  const { send, cancel, inFlight } = useAssistantConversation();
  const [current, setCurrent] = useState<{ sessionId: string; userMsgId: string } | null>(null);
  const session = useAssistantStore((s) => (current ? s.sessions.find((x) => x.id === current.sessionId) : undefined));

  const question = current ? session?.messages.find((m) => m.id === current.userMsgId) : undefined;
  let turn: HomeAnswerTurn | null = null;
  if (current && question) {
    const reply = session!.messages.find((m) => m.replyTo === current.userMsgId);
    const state: HomeAnswerState = question.status === 'sending' ? 'pending'
      : reply ? (reply.isError ? 'error' : 'answered')
      : 'cancelled';
    turn = {
      sessionId: current.sessionId,
      question: question.content,
      state,
      reply,
      requestId: inFlight?.userMsgId === current.userMsgId ? inFlight.requestId : null,
    };
  }

  const ask = (text: string) => {
    const trimmed = text.trim();
    if (!trimmed) return;
    const store = useAssistantStore.getState();
    const sessionId = turn ? turn.sessionId : store.startNewSession();
    const userMsgId = send(trimmed, sessionId, parsePageContext(location.pathname));
    setCurrent({ sessionId, userMsgId });
  };

  return {
    turn,
    ask,
    retry: () => { if (turn) ask(turn.question); },
    cancel: () => { if (current) cancel(current.userMsgId); },
    close: () => setCurrent(null),
    /** Opens the side panel on this conversation. */
    continueInChat: () => {
      if (!turn) return;
      const store = useAssistantStore.getState();
      store.loadSession(turn.sessionId);
      store.setOpen(true);
    },
  };
}
