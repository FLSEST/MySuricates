import { useEffect, useRef, useState } from 'react';
import { Client, ReconnectionTimeMode } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { api } from '../api/client';

/**
 * Subscribes to one broadcast topic (`/topic/...`) while `enabled`, handing every frame to `onMessage` as
 * parsed JSON. One connection per caller. Returns whether the connection is up — callers that replaced
 * polling with the topic fall back to polling while it is not (old backend, network, no ticket).
 */
export function useTopicSocket(topic: string, enabled: boolean, onMessage: (data: unknown) => void): boolean {
  const [connected, setConnected] = useState(false);
  // Kept in a ref so a new callback identity never tears the connection down.
  const callbackRef = useRef(onMessage);
  useEffect(() => { callbackRef.current = onMessage; }, [onMessage]);

  useEffect(() => {
    if (!enabled) return;

    // No API key in the handshake URL: stompjs waits for beforeConnect before every connection attempt (also before every
    // reconnect) — there we fetch a fresh one-time ticket.
    let wsTicket = '';

    const client = new Client({
      beforeConnect: async () => {
        wsTicket = await api.fetchWsTicket().catch(() => '');
      },
      // WebSocket transport only: a SockJS fallback (xhr-streaming …) opens a new session
      // and would need a second ticket — the first one is already used up by then.
      webSocketFactory: () =>
        new SockJS(`${window.location.origin}/ws?ticket=${encodeURIComponent(wsTicket)}`, null, {
          transports: ['websocket'],
        }),
      reconnectDelay: 5000,
      // Every attempt costs a POST /ws-ticket — with a dead backend, don't keep knocking every 5s forever
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      maxReconnectDelay: 60_000,
      onConnect: () => {
        setConnected(true);
        client.subscribe(topic, (message) => {
          let data: unknown;
          try {
            data = JSON.parse(message.body);
          } catch {
            return; // A malformed frame is never worth breaking the page for.
          }
          callbackRef.current(data);
        });
      },
      onDisconnect: () => setConnected(false),
      onWebSocketClose: () => setConnected(false),
      onStompError: () => setConnected(false),
    });

    client.activate();

    return () => {
      void client.deactivate();
      setConnected(false);
    };
  }, [topic, enabled]);

  return connected;
}
