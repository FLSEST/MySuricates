import { Loader2 } from 'lucide-react';
import { Button, Modal, Select } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import type { MemoryExtraction } from '../../hooks/useMemoryExtraction';
import type { WorkflowRun } from '../../types';

interface MemoryExtractModalProps {
  extraction: MemoryExtraction;
  runs?: Omit<WorkflowRun, 'steps'>[];
}

/** Dialog to extract workflow knowledge from chosen runs; shows elapsed seconds while it runs. */
export function MemoryExtractModal({ extraction, runs }: MemoryExtractModalProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  return (
    <Modal
      open={extraction.open}
      onClose={extraction.isPending ? () => {} : extraction.close}
      title={t.memory.extractKnowledge}
      size="sm"
      footer={
        extraction.isPending ? undefined : (
          <>
            <Button variant="secondary" onClick={extraction.close}>{t.common.cancel}</Button>
            <Button onClick={extraction.extract}>
              {t.memory.extractKnowledge}
            </Button>
          </>
        )
      }
    >
      {extraction.isPending ? (
        <div className="flex flex-col items-center gap-3 py-8 text-center">
          <Loader2 className="w-8 h-8 text-accent animate-spin" />
          <p className="text-sm font-medium text-text">{t.memory.extracting}</p>
          <p className="text-xs text-text-tertiary">{extraction.elapsedSeconds}s — {t.memory.extractingHint}</p>
        </div>
      ) : (
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-text mb-1.5">{t.memory.selectRuns}</label>
            <p className="text-xs text-text-tertiary mb-2">{t.memory.selectRunsHint}</p>
            <div className="max-h-40 overflow-y-auto space-y-1 border border-border rounded-lg p-2">
              {runs?.slice(0, 20).map((run) => (
                <label key={run.id} className="flex items-center gap-2 text-sm text-text py-1 px-1 rounded-sm hover:bg-surface-raised cursor-pointer">
                  <input
                    type="checkbox"
                    checked={extraction.selectedRunIds.includes(run.id)}
                    onChange={(e) => extraction.setRunSelected(run.id, e.target.checked)}
                    className="rounded-sm border-border"
                  />
                  <span className={`w-2 h-2 rounded-full ${
                    run.status === 'COMPLETED' ? 'bg-success' :
                    run.status === 'FAILED' ? 'bg-danger' : 'bg-warning'
                  }`} />
                  <span className="truncate">{formatTimestamp(run.startedAt, locale)}</span>
                  <span className="text-xs text-text-tertiary ml-auto">{run.status}</span>
                </label>
              ))}
            </div>
          </div>
          <Select
            label={t.memory.selectProvider}
            value={extraction.provider}
            onChange={(e) => extraction.setProvider(e.target.value)}
            options={[
              { value: 'CLAUDE', label: 'Claude' },
              { value: 'CODEX', label: 'Codex (OpenAI)' },
              { value: 'PI', label: 'pi (Mario Zechner)' },
            ]}
          />
        </div>
      )}
    </Modal>
  );
}
