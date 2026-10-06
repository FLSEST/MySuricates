import { useEffect, useRef, useCallback } from 'react';
import { useSyncedState } from '../../hooks/useSyncedState';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { WebLinksAddon } from '@xterm/addon-web-links';
import '@xterm/xterm/css/xterm.css';
import { useThemeAppearance } from '../../stores/themeStore';
import { api } from '../../api/client';
import { useT } from '../../i18n';
import { RefreshCw } from 'lucide-react';
import { Button, Badge } from '../ui';

type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'error';

const DARK_THEME = {
  background: '#0d1117',
  foreground: '#c9d1d9',
  cursor: '#c9d1d9',
  cursorAccent: '#0d1117',
  selectionBackground: '#264f78',
  black: '#484f58',
  red: '#ff7b72',
  green: '#3fb950',
  yellow: '#d29922',
  blue: '#58a6ff',
  magenta: '#bc8cff',
  cyan: '#39c5cf',
  white: '#b1bac4',
  brightBlack: '#6e7681',
  brightRed: '#ffa198',
  brightGreen: '#56d364',
  brightYellow: '#e3b341',
  brightBlue: '#79c0ff',
  brightMagenta: '#d2a8ff',
  brightCyan: '#56d4dd',
  brightWhite: '#f0f6fc',
};

const LIGHT_THEME = {
  background: '#ffffff',
  foreground: '#24292f',
  cursor: '#24292f',
  cursorAccent: '#ffffff',
  selectionBackground: '#0969da33',
  black: '#24292f',
  red: '#cf222e',
  green: '#116329',
  yellow: '#4d2d00',
  blue: '#0550ae',
  magenta: '#8250df',
  cyan: '#1b7c83',
  white: '#6e7781',
  brightBlack: '#57606a',
  brightRed: '#a40e26',
  brightGreen: '#1a7f37',
  brightYellow: '#633c01',
  brightBlue: '#0969da',
  brightMagenta: '#8250df',
  brightCyan: '#3192aa',
  brightWhite: '#8c959f',
};

interface TerminalPanelProps {
  runId: string;
}

export function TerminalPanel({ runId }: TerminalPanelProps) {
  const t = useT();
  const appearance = useThemeAppearance();
  const terminalRef = useRef<HTMLDivElement>(null);
  const termRef = useRef<Terminal | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  // A new runId mounts a new socket, so the status starts over at 'connecting' (reset during
  // render instead of inside the connect effect).
  const [status, setStatus] = useSyncedState<ConnectionStatus, string>(runId, () => 'connecting');

  // Opens the socket for the current runId; the caller is responsible for the 'connecting' status
  // (initial/reset state on mount, explicit set in handleReconnect).
  // A raw browser WebSocket cannot send headers during the handshake - instead of the API key
  // (which would end up in history and proxy logs) each connection attempt fetches a fresh
  // one-time ticket.
  const connect = useCallback(async () => {
    if (!termRef.current) return;

    let ticket: string;
    try {
      ticket = await api.fetchWsTicket();
    } catch {
      setStatus('error');
      return;
    }
    // During the ticket fetch the terminal may long since be unmounted (cleanup nulls termRef).
    if (!termRef.current) return;

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/terminal/${runId}?ticket=${encodeURIComponent(ticket)}`;

    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onopen = () => {
      setStatus('connected');
      // Send initial resize
      if (fitAddonRef.current && termRef.current) {
        fitAddonRef.current.fit();
        const { cols, rows } = termRef.current;
        ws.send(JSON.stringify({ type: 'resize', cols, rows }));
      }
    };

    ws.onmessage = (event) => {
      termRef.current?.write(event.data);
    };

    ws.onclose = () => {
      setStatus('disconnected');
    };

    ws.onerror = () => {
      setStatus('error');
    };
  }, [runId, setStatus]);

  useEffect(() => {
    if (!terminalRef.current) return;

    const term = new Terminal({
      cursorBlink: true,
      fontSize: 14,
      fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', Menlo, monospace",
      // theme is applied by the theme effect below (runs right after this one on mount, and on
      // every appearance change) so the terminal is not re-created when the theme switches
      scrollback: 5000,
      convertEol: true,
    });

    const fitAddon = new FitAddon();
    const webLinksAddon = new WebLinksAddon();

    term.loadAddon(fitAddon);
    term.loadAddon(webLinksAddon);
    term.open(terminalRef.current);

    termRef.current = term;
    fitAddonRef.current = fitAddon;

    // Fit after a short delay to ensure DOM is ready
    requestAnimationFrame(() => fitAddon.fit());

    // Forward keyboard input to WebSocket
    term.onData((data) => {
      const ws = wsRef.current;
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(data);
      }
    });

    // Connect WebSocket (asynchronous: first fetches a handshake ticket)
    void connect();

    // Handle resize
    const observer = new ResizeObserver(() => {
      fitAddon.fit();
      const ws = wsRef.current;
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }));
      }
    });
    observer.observe(terminalRef.current);

    return () => {
      observer.disconnect();
      wsRef.current?.close();
      term.dispose();
      termRef.current = null;
      fitAddonRef.current = null;
    };
  }, [connect]);

  // Update theme when it changes
  useEffect(() => {
    if (termRef.current) {
      termRef.current.options.theme = appearance === 'dark' ? DARK_THEME : LIGHT_THEME;
    }
  }, [appearance]);

  const handleReconnect = () => {
    wsRef.current?.close();
    termRef.current?.clear();
    setStatus('connecting');
    void connect();
  };

  const statusConfig: Record<ConnectionStatus, { variant: 'success' | 'warning' | 'danger' | 'default'; label: string }> = {
    connecting: { variant: 'warning', label: t.workflows.execution.terminal.connecting },
    connected: { variant: 'success', label: t.workflows.execution.terminal.connected },
    disconnected: { variant: 'default', label: t.workflows.execution.terminal.disconnected },
    error: { variant: 'danger', label: t.workflows.execution.terminal.error },
  };

  const { variant, label } = statusConfig[status];

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center justify-between mb-2">
        <Badge variant={variant}>{label}</Badge>
        {(status === 'disconnected' || status === 'error') && (
          <Button
            variant="secondary"
            size="sm"
            icon={<RefreshCw className="w-3.5 h-3.5" />}
            onClick={handleReconnect}
          >
            {t.workflows.execution.terminal.reconnect}
          </Button>
        )}
      </div>
      <div
        ref={terminalRef}
        className="flex-1 rounded-lg overflow-hidden border border-border"
        style={{ minHeight: '350px' }}
      />
    </div>
  );
}
