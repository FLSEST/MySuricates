import { CATEGORY_ICONS } from '../../data/skillConstants';
import { WorkflowIcon } from '../../utils/workflowIcon';
import type { Skill } from '../../types';

/** The skill's own icon (emoji or Lucide name) if it has one, otherwise its category icon. */
export function SkillIcon({ skill }: { skill: Skill }) {
  return skill.icon
    ? <span className="text-xl leading-none" data-testid="skill-icon"><WorkflowIcon icon={skill.icon} /></span>
    : <>{CATEGORY_ICONS[skill.category]}</>;
}
