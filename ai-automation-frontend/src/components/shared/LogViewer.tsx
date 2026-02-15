import { useEffect, useRef } from 'react';

interface LogEntry {
  timestamp?: string;
  level?: string;
  message: string;
}

interface LogViewerProps {
  logs: LogEntry[];
  autoScroll?: boolean;
  maxHeight?: string;
}

const levelColors: Record<string, string> = {
  INFO: 'text-accent',
  ERROR: 'text-danger',
  WARN: 'text-warning',
  DEBUG: 'text-text-tertiary',
};

export function LogViewer({ logs, autoScroll = true, maxHeight = '400px' }: LogViewerProps) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (autoScroll && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [logs, autoScroll]);

  return (
    <div
      ref={containerRef}
      className="bg-[#0a0e1a] rounded-lg border border-border overflow-auto font-mono text-xs leading-6"
      style={{ maxHeight }}
    >
      <div className="p-4 space-y-0.5">
        {logs.length === 0 && (
          <div className="text-text-tertiary animate-pulse-subtle">Waiting for logs...</div>
        )}
        {logs.map((log, i) => (
          <div key={i} className="flex gap-3 hover:bg-white/5 rounded px-1 -mx-1">
            {log.timestamp && (
              <span className="text-text-tertiary shrink-0 select-none">
                {new Date(log.timestamp).toLocaleTimeString()}
              </span>
            )}
            {log.level && (
              <span className={`shrink-0 w-12 ${levelColors[log.level] ?? 'text-text-secondary'}`}>
                {log.level}
              </span>
            )}
            <span className="text-text whitespace-pre-wrap break-all">{log.message}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
