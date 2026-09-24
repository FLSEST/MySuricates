import { useState } from 'react';
import { Input } from '../ui/Input';
import { Modal } from '../ui/Modal';
import { ModalFooter } from '../ui/ModalFooter';
import { useT } from '../../i18n';
import { useCreateOnboardingProfile } from '../../hooks/useOnboardingProfiles';

/**
 * New onboarding profile: key (lower case, digits, dashes) and names; the content starts as a
 * heading per language. The input stays when the dialog is cancelled and clears after creating.
 */
export function CreateProfileModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT();
  const createProfile = useCreateOnboardingProfile();
  const [createForm, setCreateForm] = useState({ key: '', nameDe: '', nameEn: '', content: '' });

  const handleCreate = () => {
    createProfile.mutate({
      key: createForm.key,
      name: { de: createForm.nameDe, en: createForm.nameEn },
      description: { de: '', en: '' },
      content: {
        de: '## ' + (createForm.nameDe || createForm.nameEn) + '\n\n',
        en: '## ' + createForm.nameEn + '\n\n',
      },
      type: 'PROFILE',
    }, {
      onSuccess: () => {
        onClose();
        setCreateForm({ key: '', nameDe: '', nameEn: '', content: '' });
      },
    });
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.onboardingProfiles.create}
      footer={
        <ModalFooter
          onCancel={onClose}
          onAction={handleCreate}
          cancelLabel={t.common.cancel}
          actionLabel={t.common.create}
          actionLoading={createProfile.isPending}
          actionDisabled={!createForm.key || !createForm.nameEn}
        />
      }
    >
      <div className="space-y-4">
        <Input
          label={t.onboardingProfiles.key}
          value={createForm.key}
          onChange={e => setCreateForm(f => ({ ...f, key: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '') }))}
          placeholder="my-profile"
          hint={t.onboardingProfiles.keyHint}
        />
        <div className="grid grid-cols-2 gap-3">
          <Input
            label={t.onboardingProfiles.nameDe}
            value={createForm.nameDe}
            onChange={e => setCreateForm(f => ({ ...f, nameDe: e.target.value }))}
          />
          <Input
            label={t.onboardingProfiles.nameEn}
            value={createForm.nameEn}
            onChange={e => setCreateForm(f => ({ ...f, nameEn: e.target.value }))}
          />
        </div>
      </div>
    </Modal>
  );
}
