import { useEffect, useRef, useState, useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

interface StepMessage {
  stepIndex: number;
  status: string;
  input: string;
  output: string;
  durationMs: number;
}

interface StatusMessage {
  type: 'status';
  runId: string;
  status: string;
  stepsCompleted?: number;
}

type WsMessage = StepMessage | StatusMessage;

function isStatusMessage(data: WsMessage): data is StatusMessage {
  return 'type' in data && data.type === 'status';
}

export function useExecutionWebSocket(runId: string | null, workflowId?: string) {
  const [logs, setLogs] = useState<Array<{ timestamp: string; level: string; message: string }>>([]);
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);
  const queryClient = useQueryClient();

  useEffect(() => {
    if (!runId) return;

    const client = new Client({
      webSocketFactory: () => new SockJS(`${window.location.origin}/ws`),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        client.subscribe(`/topic/executions/${runId}/logs`, (message) => {
          try {
            const data = JSON.parse(message.body) as WsMessage;

            if (isStatusMessage(data)) {
              setLogs((prev) => [
                ...prev,
                {
                  timestamp: new Date().toISOString(),
                  level: 'INFO',
                  message: `Run status: ${data.status}${data.stepsCompleted != null ? ` (${data.stepsCompleted} steps completed)` : ''}`,
                },
              ]);
            } else {
              setLogs((prev) => [
                ...prev,
                {
                  timestamp: new Date().toISOString(),
                  level: data.status === 'FAILED' ? 'ERROR' : 'INFO',
                  message: `[Step ${data.stepIndex}] ${data.output || data.input}`,
                },
              ]);
            }

            // Invalidate run queries so steps update live
            if (workflowId) {
              queryClient.invalidateQueries({ queryKey: ['workflows', workflowId, 'runs'] });
            }
          } catch {
            setLogs((prev) => [
              ...prev,
              { timestamp: new Date().toISOString(), level: 'INFO', message: message.body },
            ]);
          }
        });
      },
      onDisconnect: () => setConnected(false),
      onStompError: () => setConnected(false),
    });

    clientRef.current = client;
    client.activate();

    return () => {
      client.deactivate();
      clientRef.current = null;
    };
  }, [runId]);

  const clearLogs = useCallback(() => setLogs([]), []);

  return { logs, connected, clearLogs };
}
