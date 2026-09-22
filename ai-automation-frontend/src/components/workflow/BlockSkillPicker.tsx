import { useT } from '../../i18n';
import { blockUsesSkills } from './blockEdit';

interface BlockSkillPickerProps {
  blockType: string;
  configJson: string;
  skills: Array<{ id: string; name: string }>;
  selected: string[];
  onChange: (skillIds: string[]) => void;
}

/**
 * Per-block skills, offered only where they take effect (see `blockUsesSkills`). A block that cannot use
 * skills but already carries some keeps them visible with a hint, so nothing disappears silently.
 */
export function BlockSkillPicker({ blockType, configJson, skills, selected, onChange }: BlockSkillPickerProps) {
  const t = useT();
  const usesSkills = blockUsesSkills(blockType, configJson);
  const shown = usesSkills ? skills : skills.filter((skill) => selected.includes(skill.id));
  if (shown.length === 0) return null;

  const toggle = (id: string) => onChange(selected.includes(id) ? selected.filter((s) => s !== id) : [...selected, id]);

  return (
    <div data-testid="block-skill-picker">
      <label className="block text-sm font-medium text-text mb-1">{t.workflows.blockSkills}</label>
      <p className={`text-xs mb-2 ${usesSkills ? 'text-text-tertiary' : 'text-warning'}`}>
        {usesSkills ? t.workflows.blockSkillsHint : t.workflows.blockSkillsNoEffect}
      </p>
      <div className="flex flex-wrap gap-1.5">
        {shown.map((skill) => (
          <button
            key={skill.id}
            type="button"
            onClick={() => toggle(skill.id)}
            aria-pressed={selected.includes(skill.id)}
            className={`px-2 py-1 rounded-md text-xs border transition-colors ${
              selected.includes(skill.id)
                ? 'bg-accent/10 border-accent text-accent'
                : 'border-border text-text-tertiary hover:border-accent/50'
            }`}
          >
            {skill.name}
          </button>
        ))}
      </div>
    </div>
  );
}
