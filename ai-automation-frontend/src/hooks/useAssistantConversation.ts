import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useAssistantStore } from '../stores/assistantStore';
import { useSettingsStore } from '../stores/settingsStore';
import { getTranslations, useI18nStore } from '../i18n';
import { generateId } from '../utils/uuid';
import { postAssistantChat, type AssistantPageContext, type ChatMessage } from './useAssistant';

/** What a turn is sent with, fixed when it is asked (a queued turn keeps its own context). */
interface TurnParams {
  context: AssistantPageContext;
  provider?: string;
  language: string;
  queryClient: QueryClient;
}

interface Turn {
  text: string;
  sessionId: string;
  userMsgId: string;
  params: TurnParams;
}

// The conversation lives outside React: the answer lands in the store even when the page that asked
// is gone (a mutation's per-call callbacks would not fire after unmount), and panel and Simple home
// share one request at a time and one queue.
let controller: AbortController | null = null;
const queue: Turn[] = [];

function dispatch(turn: Turn) {
  const store = useAssistantStore.getState();
  const { text, sessionId, userMsgId, params } = turn;
  // History: everything so far except this message and turns still waiting in the queue
  const history = (store.sessions.find((s) => s.id === sessionId)?.messages ?? [])
    .filter((m) => m.id !== userMsgId && m.status !== 'sending')
    .map((m) => ({ role: m.role, content: m.content }));

  const ctrl = new AbortController();
  controller = ctrl;
  const requestId = generateId();
  store.setInFlight({ sessionId, requestId, userMsgId });

  const settle = () => {
    if (controller === ctrl) controller = null;
    const s = useAssistantStore.getState();
    if (s.inFlight?.requestId === requestId) s.setInFlight(null);
  };

  postAssistantChat({
    message: text, history, context: params.context, provider: params.provider,
    language: params.language, sessionId, requestId,
  }, ctrl.signal).then(
    (res) => {
      settle();
      const s = useAssistantStore.getState();
      s.updateMessageStatus(sessionId, userMsgId, 'sent');
      if (res.model) s.setLastModel(res.model);
      s.appendMessage(sessionId, {
        id: generateId(),
        role: 'assistant',
        content: res.message,
        actions: res.actions,
        responseTimeMs: res.responseTimeMs ?? undefined,
        provider: res.provider ?? undefined,
        model: res.model ?? undefined,
        pendingTools: res.pendingTools,
        timestamp: Date.now(),
        replyTo: userMsgId,
      });
      params.queryClient.invalidateQueries({ queryKey: ['assistant-sessions-api'] });
      next();
    },
    (err: unknown) => {
      settle();
      const s = useAssistantStore.getState();
      // Cancelled by the user: the message stays, without a status and without an error bubble
      const isAbort = ctrl.signal.aborted || (err instanceof DOMException && err.name === 'AbortError');
      if (isAbort) {
        s.updateMessageStatus(sessionId, userMsgId, undefined);
      } else {
        s.updateMessageStatus(sessionId, userMsgId, 'error');
        s.appendMessage(sessionId, {
          id: generateId(),
          role: 'assistant',
          // In the language of the moment the answer failed; the text is stored with the message
          content: getTranslations().assistant.errorReply,
          timestamp: Date.now(),
          isError: true,
          replyTo: userMsgId,
        });
      }
      next();
    },
  );
}

function next() {
  const turn = queue.shift();
  if (turn) dispatch(turn);
}

/**
 * Appends `text` as a user message to `sessionId` and sends it — or queues it while another request
 * is on its way (the message shows as "sending" meanwhile). Returns the user message's id; the
 * answer carries it as `replyTo`.
 */
export function sendAssistantMessage(text: string, sessionId: string, params: TurnParams): string {
  const store = useAssistantStore.getState();
  const userMsg: ChatMessage = { id: generateId(), role: 'user', content: text, timestamp: Date.now(), status: 'sending' };
  store.appendMessage(sessionId, userMsg);
  const turn: Turn = { text, sessionId, userMsgId: userMsg.id, params };
  if (store.inFlight) queue.push(turn);
  else dispatch(turn);
  return userMsg.id;
}

/**
 * Cancels the request on its way — or, with `userMsgId`, that one message: aborted if it is the one
 * on its way, taken out of the queue if it still waits.
 */
export function cancelAssistantMessage(userMsgId?: string) {
  const store = useAssistantStore.getState();
  const inFlight = store.inFlight;
  if (!userMsgId || inFlight?.userMsgId === userMsgId) {
    controller?.abort();
    controller = null;
    store.setInFlight(null);
    return;
  }
  const i = queue.findIndex((q) => q.userMsgId === userMsgId);
  if (i >= 0) {
    const [turn] = queue.splice(i, 1);
    store.updateMessageStatus(turn!.sessionId, userMsgId, undefined);
  }
}

/** Test helper: forgets a queue left behind by an unfinished test. */
export function resetAssistantConversation() {
  controller?.abort();
  controller = null;
  queue.length = 0;
  useAssistantStore.getState().setInFlight(null);
}

/**
 * Talking to the assistant from any place (panel, Simple home): `send` takes the text, the session
 * and the page context; provider and reply language come from the settings. `isPending` is true
 * while any request is on its way — there is only one at a time.
 */
export function useAssistantConversation() {
  const queryClient = useQueryClient();
  const defaultAiProvider = useSettingsStore((s) => s.defaultAiProvider);
  const locale = useI18nStore((s) => s.locale);
  const inFlight = useAssistantStore((s) => s.inFlight);

  const send = (text: string, sessionId: string, context: AssistantPageContext) =>
    sendAssistantMessage(text, sessionId, {
      context, provider: defaultAiProvider || undefined, language: locale, queryClient,
    });

  return { send, cancel: cancelAssistantMessage, isPending: inFlight !== null, inFlight };
}
