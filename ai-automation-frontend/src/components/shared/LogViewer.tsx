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
  INFO: 'text-sky-400',
  ERROR: 'text-red-400',
  WARN: 'text-amber-400',
  DEBUG: 'text-gray-500',
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
          <div className="text-gray-500 animate-pulse-subtle">Waiting for logs...</div>
        )}
        {logs.map((log, i) => (
          <div key={i} className="flex gap-3 hover:bg-white/5 rounded px-1 -mx-1">
            {log.timestamp && (
              <span className="text-gray-500 shrink-0 select-none">
                {new Date(log.timestamp).toLocaleTimeString()}
              </span>
            )}
            {log.level && (
              <span className={`shrink-0 w-12 ${levelColors[log.level] ?? 'text-gray-400'}`}>
                {log.level}
              </span>
            )}
            <span className="text-gray-200 whitespace-pre-wrap break-all">{log.message}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
