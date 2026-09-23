import { useRef, useState } from 'react';
import { Input, Modal, ModalFooter, Select } from '../ui';
import { MonacoEditor } from '../shared/MonacoEditor';
import { useCreateSkill, useUpdateSkill } from '../../hooks/useSkills';
import { SKILL_CATEGORIES } from '../../data/skillConstants';
import { useT } from '../../i18n';
import type { Skill, SkillInput } from '../../types';

const EMPTY: SkillInput = { name: '', description: null, icon: null, category: 'PRODUCTIVITY', aiContext: null };

function fromSkill(skill: Skill): SkillInput {
  return {
    name: skill.name,
    description: skill.description,
    icon: skill.icon,
    category: skill.category,
    aiContext: skill.aiContext,
  };
}

/** Empty strings go out as null, so an untouched optional field stays unset. */
function normalized(form: SkillInput): SkillInput {
  const orNull = (v: string | null) => (v && v.trim() !== '' ? v : null);
  return {
    name: form.name.trim(),
    description: orNull(form.description),
    icon: orNull(form.icon?.trim() ?? null),
    category: form.category,
    aiContext: orNull(form.aiContext),
  };
}

/**
 * Create or edit a custom skill. With `skill` it edits (PUT), otherwise it creates (POST) —
 * prefilled from `initial` when a skill is copied. Mount it with a `key` per target: the form
 * takes its starting values once.
 */
export function SkillEditorModal({
  skill,
  initial,
  copied = false,
  onClose,
  onSaved,
}: {
  skill?: Skill;
  initial?: SkillInput;
  /** Prefilled from another skill: says that capabilities were not copied. */
  copied?: boolean;
  onClose: () => void;
  onSaved?: (skill: Skill) => void;
}) {
  const t = useT();
  const createSkill = useCreateSkill();
  const updateSkill = useUpdateSkill();
  const [form, setForm] = useState<SkillInput>(() => (skill ? fromSkill(skill) : initial ?? EMPTY));
  // The AI context is uncontrolled: @monaco-editor/react replaces the whole model whenever `value`
  // differs from it, so echoing each keystroke back through state lets a slower re-render write an
  // older text over newer keystrokes (measured: characters lost at 20–40 ms per key). Read on save.
  const [initialContext] = useState(() => form.aiContext ?? '');
  const aiContext = useRef(initialContext);
  const pending = createSkill.isPending || updateSkill.isPending;

  const save = () => {
    const data = normalized({ ...form, aiContext: aiContext.current });
    const done = { onSuccess: (saved: Skill) => { onSaved?.(saved); onClose(); } };
    if (skill) updateSkill.mutate({ id: skill.id, data }, done);
    else createSkill.mutate(data, done);
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={skill ? t.skills.editSkill : t.skills.newSkill}
      size="xl"
      resizable
      footer={
        <ModalFooter
          onCancel={onClose}
          onAction={save}
          cancelLabel={t.common.cancel}
          actionLabel={skill ? t.common.save : t.common.create}
          actionLoading={pending}
          actionDisabled={!form.name.trim() || pending}
        />
      }
    >
      <div className="flex flex-col flex-1 gap-4" data-testid="skill-editor">
        {copied && <p className="text-sm text-text-secondary">{t.skills.copyHint}</p>}
        <div className="grid grid-cols-1 sm:grid-cols-[1fr_8rem_12rem] gap-3">
          <Input
            label={t.common.name}
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            autoFocus
          />
          <Input
            label={t.skills.iconLabel}
            value={form.icon ?? ''}
            placeholder="🧩"
            onChange={(e) => setForm({ ...form, icon: e.target.value })}
          />
          <Select
            label={t.skills.categoryLabel}
            value={form.category}
            options={SKILL_CATEGORIES.map((c) => ({ value: c, label: t.skills.categories[c] }))}
            onChange={(e) => setForm({ ...form, category: e.target.value as SkillInput['category'] })}
          />
        </div>
        <div className="space-y-1.5">
          <label htmlFor="skill-description" className="block text-sm font-medium text-text-secondary">
            {t.common.description}
          </label>
          <textarea
            id="skill-description"
            className="input-base w-full min-h-[64px] resize-y"
            value={form.description ?? ''}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
          />
        </div>
        <div className="flex flex-col flex-1 min-h-[280px]">
          <label className="block text-sm font-medium text-text mb-0.5">{t.skills.aiContext}</label>
          <p className="text-xs text-text-tertiary mb-1.5">{t.skills.aiContextHint}</p>
          <MonacoEditor
            value={initialContext}
            onChange={(v) => { aiContext.current = v; }}
            language="markdown"
            prose
            fill
          />
        </div>
      </div>
    </Modal>
  );
}
