import { useEffect, useRef, useState, useCallback } from 'react';

interface WebSocketMessage {
  stepIndex: number;
  status: string;
  input: string;
  output: string;
  durationMs: number;
}

export function useExecutionWebSocket(runId: string | null) {
  const [logs, setLogs] = useState<Array<{ timestamp: string; level: string; message: string }>>([]);
  const [connected, setConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);

  const connect = useCallback(() => {
    if (!runId) return;

    const wsUrl = `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws/executions/${runId}/logs`;

    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onopen = () => setConnected(true);
    ws.onclose = () => setConnected(false);
    ws.onerror = () => setConnected(false);

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data) as WebSocketMessage;
        setLogs((prev) => [
          ...prev,
          {
            timestamp: new Date().toISOString(),
            level: data.status === 'FAILED' ? 'ERROR' : 'INFO',
            message: `[Step ${data.stepIndex}] ${data.output || data.input}`,
          },
        ]);
      } catch {
        setLogs((prev) => [
          ...prev,
          { timestamp: new Date().toISOString(), level: 'INFO', message: event.data },
        ]);
      }
    };
  }, [runId]);

  useEffect(() => {
    connect();
    return () => {
      wsRef.current?.close();
    };
  }, [connect]);

  const clearLogs = useCallback(() => setLogs([]), []);

  return { logs, connected, clearLogs };
}
