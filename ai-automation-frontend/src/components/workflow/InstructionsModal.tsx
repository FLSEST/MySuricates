import { Modal, ModalFooter } from '../ui';
import { MonacoEditor } from '../shared/MonacoEditor';
import { useT } from '../../i18n';

interface InstructionsModalProps {
  open: boolean;
  onClose: () => void;
  instructions: string;
  setInstructions: (v: string) => void;
  onSave: () => void;
  saving: boolean;
}

/** The per-workflow agent instructions (Markdown). */
export function InstructionsModal({ open, onClose, instructions, setInstructions, onSave, saving }: InstructionsModalProps) {
  const t = useT();
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.workflows.editInstructions}
      size="lg"
      footer={
        <ModalFooter
          onCancel={onClose}
          onAction={onSave}
          cancelLabel={t.common.cancel}
          actionLabel={t.common.save}
          actionLoading={saving}
        />
      }
    >
      <div className="space-y-3">
        <p className="text-sm text-text-secondary">{t.workflows.instructionsHint}</p>
        <MonacoEditor
          value={instructions}
          onChange={setInstructions}
          language="markdown"
          height="300px"
          prose
        />
      </div>
    </Modal>
  );
}
