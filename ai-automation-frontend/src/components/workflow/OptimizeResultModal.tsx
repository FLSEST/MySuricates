import { Modal, ModalFooter } from '../ui';
import { useT } from '../../i18n';
import type { OptimizeResult } from '../../types';

interface OptimizeResultModalProps {
  open: boolean;
  onClose: () => void;
  result: OptimizeResult | null;
  onApply: () => void;
  applying: boolean;
}

/** What the optimizer proposes — changes, instructions and blocks — before it is applied. */
export function OptimizeResultModal({ open, onClose, result, onApply, applying }: OptimizeResultModalProps) {
  const t = useT();
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.workflows.optimizeResult}
      size="lg"
      footer={
        <ModalFooter
          onCancel={onClose}
          onAction={onApply}
          cancelLabel={t.common.cancel}
          actionLabel={t.workflows.optimizeApply}
          actionLoading={applying}
        />
      }
    >
      {result && (
        <div className="space-y-4">
          {result.error ? (
            <p className="text-sm text-danger">{String(result.message)}</p>
          ) : (
            <>
              <div>
                <h4 className="text-sm font-medium text-text mb-2">{t.workflows.optimizeChanges}</h4>
                {Array.isArray(result.changes) && result.changes.length > 0 ? (
                  <ul className="space-y-2">
                    {result.changes!.map((change, i) => (
                      <li key={i} className="flex items-start gap-2 text-sm">
                        <span className="shrink-0 px-1.5 py-0.5 rounded-sm text-[10px] font-medium bg-accent/10 text-accent">
                          {change.type}
                        </span>
                        <span className="text-text-secondary">{change.description}</span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="text-sm text-text-tertiary">{t.workflows.optimizeNoChanges}</p>
                )}
              </div>
              {result.suggestedInstructions && (
                <div>
                  <h4 className="text-sm font-medium text-text mb-1">{t.workflows.instructions}</h4>
                  <pre className="text-xs text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded-lg p-3 max-h-40 overflow-y-auto">
                    {String(result.suggestedInstructions)}
                  </pre>
                </div>
              )}
              {Array.isArray(result.blocks) && result.blocks.length > 0 && (
                <div>
                  <h4 className="text-sm font-medium text-text mb-1">{t.workflows.blocks} ({result.blocks!.length})</h4>
                  <pre className="text-xs text-text-secondary font-mono bg-surface rounded-lg p-3 max-h-60 overflow-y-auto">
                    {JSON.stringify(result.blocks, null, 2)}
                  </pre>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </Modal>
  );
}
