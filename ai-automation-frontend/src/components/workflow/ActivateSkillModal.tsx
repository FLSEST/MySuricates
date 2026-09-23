import { Select, Modal, ModalFooter } from '../ui';
import { useT } from '../../i18n';
import type { SkillOption } from './BlockEditorModal';

interface ActivateSkillModalProps {
  open: boolean;
  onClose: () => void;
  selectedSkillId: string;
  setSelectedSkillId: (v: string) => void;
  availableSkills: SkillOption[];
  onActivate: () => void;
  activating: boolean;
}

/** Activates one more skill for the whole workflow. */
export function ActivateSkillModal({
  open, onClose, selectedSkillId, setSelectedSkillId, availableSkills, onActivate, activating,
}: ActivateSkillModalProps) {
  const t = useT();
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.workflows.activateSkill}
      size="sm"
      footer={
        <ModalFooter
          onCancel={onClose}
          onAction={onActivate}
          cancelLabel={t.common.cancel}
          actionLabel={t.skills.activate}
          actionDisabled={!selectedSkillId}
          actionLoading={activating}
        />
      }
    >
      <Select
        label={t.skills.title}
        value={selectedSkillId}
        onChange={(e) => setSelectedSkillId(e.target.value)}
        options={[
          { value: '', label: t.common.selectSkill },
          ...availableSkills.map((s) => ({ value: s.id, label: s.name })),
        ]}
      />
    </Modal>
  );
}
