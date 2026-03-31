import { useEffect, useRef, useState, useCallback } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

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

  // Connect once when either ID is available
  useEffect(() => {
    if (!requestId && !sessionId) return;

    // Reuse existing client if connected
    if (clientRef.current?.connected) return;

    const apiKey = localStorage.getItem('app-settings')
      ? JSON.parse(localStorage.getItem('app-settings') || '{}')?.apiKey
      : undefined;
    const wsUrl = `${window.location.origin}/ws${apiKey ? `?apiKey=${apiKey}` : ''}`;

    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      reconnectDelay: 5000,
      onConnect: () => setConnected(true),
      onDisconnect: () => setConnected(false),
      onStompError: () => setConnected(false),
    });

    clientRef.current = client;
    client.activate();

    return () => {
      client.deactivate();
      clientRef.current = null;
      setConnected(false);
    };
  }, [!!requestId || !!sessionId]); // reconnect only when going from no-IDs to having IDs

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
