import { useState, lazy, Suspense } from 'react';
import { Send, TerminalSquare } from 'lucide-react';
import { Card, Button, Spinner, Input } from '../ui';
import { useRunCommand } from '../../hooks/useWorkflows';
import { useModuleEnabled } from '../../hooks/useModules';
import { useT } from '../../i18n';

const TerminalPanel = lazy(() => import('../shared/TerminalPanel').then(m => ({ default: m.TerminalPanel })));

/** Eingriff in einen laufenden Run: einzelnes Kommando senden oder (Modul `terminal`) interaktives Terminal. */
export function RunContainerPanel({ runId }: { runId: string }) {
  const t = useT();
  const terminalModule = useModuleEnabled('terminal');
  const runCommand = useRunCommand();
  const [command, setCommand] = useState('');
  const [containerTab, setContainerTab] = useState<'command' | 'terminal'>('command');

  return (
    <Card>
      <div className="flex items-center gap-1 mb-4 border-b border-border">
        <button
          onClick={() => setContainerTab('command')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
            containerTab === 'command'
              ? 'border-accent text-accent'
              : 'border-transparent text-text-secondary hover:text-text'
          }`}
        >
          <span className="flex items-center gap-2">
            <Send className="w-4 h-4" />
            {t.workflows.execution.command}
          </span>
        </button>
        {terminalModule && (
          <button
            onClick={() => setContainerTab('terminal')}
            className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
              containerTab === 'terminal'
                ? 'border-accent text-accent'
                : 'border-transparent text-text-secondary hover:text-text'
            }`}
          >
            <span className="flex items-center gap-2">
              <TerminalSquare className="w-4 h-4" />
              {t.workflows.execution.terminal.title}
            </span>
          </button>
        )}
      </div>

      {containerTab === 'command' && (
        <div className="flex gap-2">
          <div className="flex-1">
            <Input
              value={command}
              onChange={(e) => setCommand(e.target.value)}
              placeholder={t.workflows.execution.commandPlaceholder}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && command.trim()) {
                  runCommand.mutate({ runId, command: command.trim() });
                  setCommand('');
                }
              }}
            />
          </div>
          <Button
            icon={<Send className="w-4 h-4" />}
            onClick={() => {
              if (command.trim()) {
                runCommand.mutate({ runId, command: command.trim() });
                setCommand('');
              }
            }}
            loading={runCommand.isPending}
            disabled={!command.trim()}
          >
            {t.workflows.execution.sendCommand}
          </Button>
        </div>
      )}

      {containerTab === 'terminal' && terminalModule && (
        <Suspense fallback={<Spinner text={t.common.loading} />}>
          <TerminalPanel runId={runId} />
        </Suspense>
      )}
    </Card>
  );
}
