import { Lock } from 'lucide-react';
import { Card, Badge } from '../ui';
import { useSkillSealPins } from '../../hooks/useSealStatus';
import { useT } from '../../i18n';
import { CATEGORY_COLORS } from '../../data/skillConstants';
import type { Skill } from '../../types';
import { SkillIcon } from './SkillIcon';

/** Grid card of a skill; a lock marks skills a sealed workflow pins. */
export function SkillCard({ skill, onSelect }: { skill: Skill; onSelect: (s: Skill) => void }) {
  const t = useT();
  const { data: sealPins } = useSkillSealPins();
  const sealedBy = sealPins?.[skill.id] ?? [];
  return (
    <Card
      className="cursor-pointer hover:border-border-strong transition-all group"
      onClick={() => onSelect(skill)}
    >
      <div className="flex items-start gap-3 mb-3">
        <div className={`p-2.5 rounded-xl bg-surface flex items-center justify-center w-10 h-10 ${CATEGORY_COLORS[skill.category]}`}>
          <SkillIcon skill={skill} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <h3 className="font-semibold text-text truncate">{skill.name}</h3>
            {skill.builtin && (
              <Badge variant="accent">{t.skills.builtin}</Badge>
            )}
            {sealedBy.length > 0 && (
              <span title={t.skills.sealedByHint.replace('{names}', sealedBy.join(', '))} data-testid="skill-sealed"
                    className="shrink-0 inline-flex">
                <Lock className="w-3.5 h-3.5 text-accent" aria-label={t.skills.sealedBy} />
              </span>
            )}
          </div>
          <p className="text-xs text-text-tertiary mt-0.5">
            {t.skills.categories[skill.category]}
          </p>
        </div>
      </div>
      <p className="text-sm text-text-secondary line-clamp-2 mb-3">{skill.description}</p>
      <div className="flex items-center justify-between text-xs text-text-tertiary">
        <span>{t.skills.capabilityCount.replace('{count}', String(skill.capabilities?.length ?? 0))}</span>
        <span className="font-mono">v{skill.version}</span>
      </div>
    </Card>
  );
}
