import { useState } from 'react';
import { Modal } from '../ui/Modal';
import { ModalFooter } from '../ui/ModalFooter';
import { Spinner } from '../ui/Spinner';
import { useI18nStore, useT } from '../../i18n';
import { useShippedOnboardingProfile } from '../../hooks/useOnboardingProfiles';
import type { ContentLanguage } from '../../utils/onboardingProfileText';
import { ContentLanguageToggle } from './ContentLanguageToggle';

/** Shipped text of a built-in profile with an update, to compare before taking it over. */
export function ShippedModal({ profileId, open, onClose, onTakeOver, takingOver }: {
  profileId: string; open: boolean; onClose: () => void; onTakeOver: () => void; takingOver: boolean;
}) {
  const t = useT();
  const locale = useI18nStore(s => s.locale);
  const { data: shipped, isLoading } = useShippedOnboardingProfile(profileId, open);
  const [lang, setLang] = useState<ContentLanguage>(locale === 'de' ? 'de' : 'en');

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.onboardingProfiles.shippedTitle.replace('{version}', () => String(shipped?.version ?? ''))}
      size="lg"
      footer={
        <ModalFooter
          onCancel={onClose}
          onAction={onTakeOver}
          cancelLabel={t.common.close}
          actionLabel={t.onboardingProfiles.takeOver}
          actionLoading={takingOver}
          actionDisabled={!shipped}
        />
      }
    >
      {isLoading || !shipped ? (
        <Spinner />
      ) : (
        <div className="space-y-2">
          <ContentLanguageToggle value={lang} onChange={setLang} />
          <p className="text-xs text-text-tertiary">{t.onboardingProfiles.resetConfirm}</p>
          <pre className="text-xs text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded p-3 max-h-[55vh] overflow-y-auto">
            {shipped.content[lang] ?? ''}
          </pre>
        </div>
      )}
    </Modal>
  );
}
