import { useEffect, useRef, useState, useCallback } from 'react';
import { Client, ReconnectionTimeMode } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { api } from '../api/client';

export interface AssistantStatus {
  type: 'assistant_status';
  requestId: string;
  phase: string;
  detail: string | null;
  elapsedMs: number;
  timestamp: string;
}

export interface AssistantPushEvent {
  type: 'assistant_event';
  sessionId: string;
  eventKind: string;
  title: string;
  detail: string | null;
  linkTo: string | null;
  timestamp: string;
}

export function useAssistantWebSocket(
  requestId: string | null,
  sessionId: string | null
) {
  const [currentPhase, setCurrentPhase] = useState<AssistantStatus | null>(null);
  const [events, setEvents] = useState<AssistantPushEvent[]>([]);
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);
  const statusSubRef = useRef<{ unsubscribe: () => void } | null>(null);

  // Connect once when either ID is available (and reconnect only when going from no IDs to
  // having IDs — the concrete IDs are handled by the subscription effects below)
  const hasIds = !!requestId || !!sessionId;
  useEffect(() => {
    if (!hasIds) return;

    // Reuse existing client if connected
    if (clientRef.current?.connected) return;

    // Kein API-Key in der Handshake-URL: stompjs wartet vor jedem Verbindungsversuch (auch vor
    // jedem Reconnect) auf beforeConnect — dort holen wir ein frisches Einmal-Ticket.
    let wsTicket = '';
    let disposed = false;

    const client = new Client({
      beforeConnect: async () => {
        wsTicket = await api.fetchWsTicket().catch(() => '');
      },
      // Nur der WebSocket-Transport: ein SockJS-Fallback (xhr-streaming …) eröffnet eine neue
      // Session und bräuchte ein zweites Ticket — das erste ist dann schon verbraucht.
      webSocketFactory: () =>
        new SockJS(`${window.location.origin}/ws?ticket=${encodeURIComponent(wsTicket)}`, null, {
          transports: ['websocket'],
        }),
      reconnectDelay: 5000,
      // Jeder Versuch kostet einen POST /ws-ticket — bei totem Backend nicht ewig im 5s-Takt klopfen
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      maxReconnectDelay: 60_000,
      onConnect: () => setConnected(true),
      onDisconnect: () => setConnected(false),
      onStompError: () => setConnected(false),
      // onDisconnect needs a DISCONNECT receipt; an abrupt close (backend restart, standby) only ends up here –
      // without it `connected` stayed true and the reconnect did not subscribe the topics again (c5f3cff5).
      // A socket this effect already left must not switch off the next one.
      onWebSocketClose: () => {
        if (!disposed) setConnected(false);
      },
    });

    clientRef.current = client;
    client.activate();

    return () => {
      disposed = true;
      client.deactivate();
      clientRef.current = null;
      setConnected(false);
    };
  }, [hasIds]);

  // Subscribe to status updates for current requestId
  useEffect(() => {
    const client = clientRef.current;
    if (!client?.connected || !requestId) {
      statusSubRef.current?.unsubscribe();
      statusSubRef.current = null;
      return;
    }

    const sub = client.subscribe(
      `/topic/assistant/${requestId}/status`,
      (message) => {
        try {
          const data = JSON.parse(message.body) as AssistantStatus;
          setCurrentPhase(data);
          // Clear phase after terminal states
          if (data.phase === 'COMPLETED' || data.phase === 'ERROR') {
            setTimeout(() => setCurrentPhase(null), 500);
          }
        } catch {
          // ignore parse errors
        }
      }
    );
    statusSubRef.current = sub;

    return () => {
      sub.unsubscribe();
      statusSubRef.current = null;
    };
  }, [requestId, connected]);

  // Subscribe to push events for current sessionId
  useEffect(() => {
    const client = clientRef.current;
    if (!client?.connected || !sessionId) return;

    const sub = client.subscribe(
      `/topic/assistant/${sessionId}/events`,
      (message) => {
        try {
          const data = JSON.parse(message.body) as AssistantPushEvent;
          setEvents((prev) => [...prev, data]);
        } catch {
          // ignore parse errors
        }
      }
    );

    return () => sub.unsubscribe();
  }, [sessionId, connected]);

  const clearEvents = useCallback(() => setEvents([]), []);

  return { currentPhase, events, connected, clearEvents };
}
