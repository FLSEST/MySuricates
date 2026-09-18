import { useEffect, useRef, useState } from 'react';
import { Client, ReconnectionTimeMode } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { api } from '../api/client';

/** What the backend sends on /topic/dashboard/runs — ids and the status enum, nothing else. */
export interface RunCompletedMessage {
  type: 'run_completed';
  runId: string;
  workflowId: string;
  status: string;
}

const TOPIC = '/topic/dashboard/runs';

/**
 * Tells the dashboard that a run has ended, so the workflow widgets reload instead of polling every
 * 30 seconds per instance (spec E3). One connection per dashboard, only while workflow widgets are
 * shown. Returns whether the connection is up — the caller falls back to polling while it is not.
 */
export function useRunCompletedSocket(enabled: boolean, onRunCompleted: (message: RunCompletedMessage) => void): boolean {
  const [connected, setConnected] = useState(false);
  // Kept in a ref so a new callback identity never tears the connection down.
  const callbackRef = useRef(onRunCompleted);
  useEffect(() => { callbackRef.current = onRunCompleted; }, [onRunCompleted]);

  useEffect(() => {
    if (!enabled) return;

    // Kein API-Key in der Handshake-URL: stompjs wartet vor jedem Verbindungsversuch (auch vor jedem
    // Reconnect) auf beforeConnect — dort holen wir ein frisches Einmal-Ticket.
    let wsTicket = '';

    const client = new Client({
      beforeConnect: async () => {
        wsTicket = await api.fetchWsTicket().catch(() => '');
      },
      // Nur der WebSocket-Transport: ein SockJS-Fallback (xhr-streaming …) eröffnet eine neue Session
      // und bräuchte ein zweites Ticket — das erste ist dann schon verbraucht.
      webSocketFactory: () =>
        new SockJS(`${window.location.origin}/ws?ticket=${encodeURIComponent(wsTicket)}`, null, {
          transports: ['websocket'],
        }),
      reconnectDelay: 5000,
      // Jeder Versuch kostet einen POST /ws-ticket — bei totem Backend nicht ewig im 5s-Takt klopfen
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      maxReconnectDelay: 60_000,
      onConnect: () => {
        setConnected(true);
        client.subscribe(TOPIC, (message) => {
          try {
            const data = JSON.parse(message.body) as Partial<RunCompletedMessage>;
            if (data?.type === 'run_completed' && typeof data.workflowId === 'string' && typeof data.runId === 'string') {
              callbackRef.current(data as RunCompletedMessage);
            }
          } catch {
            // A malformed frame is never worth breaking the dashboard for.
          }
        });
      },
      onDisconnect: () => setConnected(false),
      onStompError: () => setConnected(false),
    });

    client.activate();

    return () => {
      void client.deactivate();
      setConnected(false);
    };
  }, [enabled]);

  return connected;
}
