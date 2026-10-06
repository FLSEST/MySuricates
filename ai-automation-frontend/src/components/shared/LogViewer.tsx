import { memo, useCallback, useLayoutEffect, useRef, useState } from 'react';
import {
  AlertTriangle, ArrowDown, CheckCircle2, ChevronRight, CornerDownRight, Info, MessageSquare, Wrench,
  type LucideIcon,
} from 'lucide-react';
import { useI18nStore, useT, type Locale } from '../../i18n';
import { formatTimeOfDay } from '../../utils/localeFormat';

export interface LogEntry {
  /** Stable key; falls back to the index for callers without ids. */
  id?: string;
  timestamp?: string;
  level?: string;
  message: string;
  /** Kind of streamed block output (line, text, tool, tool_result, tool_error, result, system). */
  kind?: string;
  stepLabel?: string;
  /** Marker entry for lines that were not received. */
  skipped?: number;
}

interface LogViewerProps {
  logs: LogEntry[];
  autoScroll?: boolean;
  maxHeight?: string;
  /** Shown instead of the pulsing "waiting for logs" when nothing will arrive (e.g. a finished run without a kept log). */
  emptyText?: string;
}

const levelColors: Record<string, string> = {
  INFO: 'text-sky-400',
  ERROR: 'text-red-400',
  WARN: 'text-amber-400',
  DEBUG: 'text-gray-500',
};

const kindStyles: Record<string, { icon: LucideIcon; iconClass: string; textClass: string }> = {
  line: { icon: ChevronRight, iconClass: 'text-gray-600', textClass: 'text-gray-200' },
  text: { icon: MessageSquare, iconClass: 'text-sky-400', textClass: 'text-gray-100' },
  tool: { icon: Wrench, iconClass: 'text-amber-400', textClass: 'text-amber-200' },
  tool_result: { icon: CornerDownRight, iconClass: 'text-gray-600', textClass: 'text-gray-500' },
  tool_error: { icon: AlertTriangle, iconClass: 'text-red-400', textClass: 'text-red-300' },
  result: { icon: CheckCircle2, iconClass: 'text-emerald-400', textClass: 'text-emerald-300' },
  system: { icon: Info, iconClass: 'text-gray-500', textClass: 'text-gray-500 italic' },
};

/** Distance from the bottom (px) that still counts as "following the log". */
const BOTTOM_THRESHOLD_PX = 24;

const LogRow = memo(function LogRow({ log, showStep, skippedText, locale }: {
  log: LogEntry;
  showStep: boolean;
  skippedText: string | null;
  locale: Locale;
}) {
  const style = log.kind ? kindStyles[log.kind] ?? kindStyles.line : null;
  const Icon = style?.icon;
  return (
    <div className="flex gap-3 hover:bg-white/5 rounded-sm px-1 -mx-1" data-kind={log.kind ?? (log.skipped != null ? 'skipped' : 'event')}>
      {log.timestamp && (
        <span className="text-gray-500 shrink-0 select-none">
          {formatTimeOfDay(log.timestamp, locale)}
        </span>
      )}
      {Icon ? (
        <span className={`shrink-0 w-12 flex items-center ${style?.iconClass ?? ''}`} title={log.kind}>
          <Icon size={12} aria-hidden="true" />
        </span>
      ) : log.level && (
        <span className={`shrink-0 w-12 ${levelColors[log.level] ?? 'text-gray-400'}`}>
          {log.level}
        </span>
      )}
      <span className={`whitespace-pre-wrap break-all min-w-0 ${skippedText ? 'text-amber-400 italic' : style?.textClass ?? 'text-gray-200'}`}>
        {showStep && log.stepLabel && (
          <span className="mr-2 px-1.5 rounded bg-white/10 text-gray-400 not-italic">{log.stepLabel}</span>
        )}
        {skippedText ?? log.message}
      </span>
    </div>
  );
});

export function LogViewer({ logs, autoScroll = true, maxHeight = '400px', emptyText }: LogViewerProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const containerRef = useRef<HTMLDivElement>(null);
  const followingRef = useRef(true);
  const [scrolledUp, setScrolledUp] = useState(false);

  const handleScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    const following = el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_THRESHOLD_PX;
    followingRef.current = following;
    setScrolledUp(!following);
  }, []);

  // Stick to the bottom only while the user follows the log; reading older lines is not interrupted.
  useLayoutEffect(() => {
    const el = containerRef.current;
    if (autoScroll && el && followingRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [logs, autoScroll]);

  const jumpToLatest = () => {
    const el = containerRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
    followingRef.current = true;
    setScrolledUp(false);
  };

  return (
    <div className="relative">
      <div
        ref={containerRef}
        onScroll={handleScroll}
        data-testid="log-viewer"
        className="bg-[#0a0e1a] rounded-lg border border-border overflow-auto font-mono text-xs leading-6"
        style={{ maxHeight }}
      >
        <div className="p-4 space-y-0.5">
          {logs.length === 0 && (emptyText
            ? <div className="text-gray-500">{emptyText}</div>
            : <div className="text-gray-500 animate-pulse-subtle">{t.workflows.execution.waitingForLogs}</div>
          )}
          {logs.map((log, i) => (
            <LogRow
              key={log.id ?? i}
              log={log}
              locale={locale}
              showStep={!!log.kind && log.stepLabel !== logs[i - 1]?.stepLabel}
              skippedText={log.skipped != null
                ? t.workflows.execution.linesSkipped.replace('{count}', String(log.skipped))
                : null}
            />
          ))}
        </div>
      </div>
      {autoScroll && scrolledUp && logs.length > 0 && (
        <button
          type="button"
          onClick={jumpToLatest}
          className="absolute bottom-3 right-3 flex items-center gap-1 rounded-full bg-accent px-3 py-1 text-xs font-medium text-text-inverse shadow-lg hover:bg-accent-hover"
        >
          <ArrowDown size={12} aria-hidden="true" />
          {t.workflows.execution.jumpToLatest}
        </button>
      )}
    </div>
  );
}
