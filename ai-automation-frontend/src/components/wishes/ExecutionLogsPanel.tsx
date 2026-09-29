import { Card } from '../ui';
import { LogViewer } from '../shared/LogViewer';
import { useExecutionWebSocket } from '../../hooks/useWebSocket';
import { useT } from '../../i18n';

export function ExecutionLogsPanel({ runId }: { runId: string }) {
  const t = useT();
  const { logs, connected } = useExecutionWebSocket(runId);

  return (
    <Card>
      <div className="p-4 space-y-3">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide">
            {t.wishes.executionLogs}
          </h3>
          {connected && (
            <span className="flex items-center gap-1.5 text-xs text-success">
              <span className="w-2 h-2 rounded-full bg-success animate-pulse" />
              {t.common.live}
            </span>
          )}
        </div>
        <LogViewer logs={logs} maxHeight="400px" />
      </div>
    </Card>
  );
}
