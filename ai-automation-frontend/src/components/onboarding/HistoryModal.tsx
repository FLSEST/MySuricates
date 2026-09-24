import { Card } from '../ui/Card';
import { Button } from '../ui/Button';
import { Modal } from '../ui/Modal';
import { Spinner } from '../ui/Spinner';
import { useI18nStore, useT } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import { useOnboardingProfileHistory } from '../../hooks/useOnboardingProfiles';

/** Earlier versions of a profile's content, newest first as the backend sends them. */
export function HistoryModal({ profileId, open, onClose }: { profileId: string; open: boolean; onClose: () => void }) {
  const t = useT();
  const locale = useI18nStore(s => s.locale);
  const { data: history, isLoading } = useOnboardingProfileHistory(profileId);

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.onboardingProfiles.history}
      size="lg"
      footer={<Button variant="secondary" onClick={onClose}>{t.common.close}</Button>}
    >
      {isLoading ? (
        <Spinner />
      ) : history && history.length > 0 ? (
        <div className="space-y-3 max-h-[60vh] overflow-y-auto">
          {history.map(h => (
            <Card key={h.id} className="p-3">
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm font-medium text-text">v{h.version}</span>
                <div className="flex items-center gap-2 text-xs text-text-tertiary">
                  <span>{h.changedBy}</span>
                  <span>{formatTimestamp(h.changedAt, locale)}</span>
                </div>
              </div>
              {Object.entries(h.content ?? {}).map(([lang, text]) => (
                <div key={lang} className="mt-1">
                  <span className="text-[10px] font-semibold uppercase text-text-tertiary">{lang}</span>
                  <pre className="text-xs text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded p-2 max-h-32 overflow-y-auto">
                    {text}
                  </pre>
                </div>
              ))}
            </Card>
          ))}
        </div>
      ) : (
        <p className="text-sm text-text-tertiary text-center py-4">{t.onboardingProfiles.historyEmpty}</p>
      )}
    </Modal>
  );
}
