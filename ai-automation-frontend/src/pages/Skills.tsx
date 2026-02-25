import { useState } from 'react';
import { Zap, Search } from 'lucide-react';
import { Card, Button, Input, Badge, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { useSkills, useSkillCapabilities } from '../hooks/useSkills';
import { useT } from '../i18n';
import { CATEGORY_ICONS, CATEGORY_COLORS, SKILL_CATEGORIES } from '../data/skillConstants';
import type { Skill, SkillCategory } from '../types';

function SkillCard({ skill, onSelect }: { skill: Skill; onSelect: (s: Skill) => void }) {
  const t = useT();
  return (
    <Card
      className="cursor-pointer hover:border-border-strong transition-all group"
      onClick={() => onSelect(skill)}
    >
      <div className="flex items-start gap-3 mb-3">
        <div className={`p-2.5 rounded-xl bg-surface ${CATEGORY_COLORS[skill.category]}`}>
          {CATEGORY_ICONS[skill.category]}
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <h3 className="font-semibold text-text truncate">{skill.name}</h3>
            {skill.builtin && (
              <Badge variant="accent">{t.skills.builtin}</Badge>
            )}
          </div>
          <p className="text-xs text-text-tertiary mt-0.5">
            {t.skills.categories[skill.category]}
          </p>
        </div>
      </div>
      <p className="text-sm text-text-secondary line-clamp-2 mb-3">{skill.description}</p>
      <div className="flex items-center justify-between text-xs text-text-tertiary">
        <span>{skill.capabilityCount} {t.skills.capabilities.toLowerCase()}</span>
        <span className="font-mono">v{skill.version}</span>
      </div>
    </Card>
  );
}

function SkillDetail({ skill, onClose }: { skill: Skill; onClose: () => void }) {
  const t = useT();
  const { data: capabilities, isLoading } = useSkillCapabilities(skill.id);

  return (
    <div className="fixed inset-0 z-50 flex">
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={onClose} />
      <div className="relative ml-auto w-full max-w-lg bg-surface-overlay border-l border-border h-full overflow-y-auto animate-slide-in">
        <div className="p-6 space-y-6">
          {/* Header */}
          <div className="flex items-start gap-3">
            <div className={`p-3 rounded-xl bg-surface ${CATEGORY_COLORS[skill.category]}`}>
              {CATEGORY_ICONS[skill.category]}
            </div>
            <div>
              <h2 className="text-xl font-bold text-text">{skill.name}</h2>
              <div className="flex items-center gap-2 mt-1">
                <Badge variant="accent">{t.skills.categories[skill.category]}</Badge>
                {skill.builtin && <Badge>{t.skills.builtin}</Badge>}
                <span className="text-xs text-text-tertiary font-mono">v{skill.version}</span>
              </div>
            </div>
          </div>

          <p className="text-sm text-text-secondary">{skill.description}</p>

          {/* Capabilities */}
          <div>
            <h3 className="text-sm font-semibold text-text mb-3">{t.skills.capabilities}</h3>
            {isLoading ? (
              <Spinner />
            ) : (
              <div className="space-y-3">
                {capabilities?.map((cap) => (
                  <div key={cap.id} className="p-3 rounded-lg bg-surface border border-border">
                    <div className="flex items-center gap-2 mb-1">
                      <Zap className="w-3.5 h-3.5 text-accent" />
                      <span className="text-sm font-medium text-text">{cap.name}</span>
                      {cap.async && <Badge variant="warning">async</Badge>}
                    </div>
                    <p className="text-xs text-text-secondary">{cap.description}</p>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* AI Context preview */}
          {skill.aiContext && (
            <div>
              <h3 className="text-sm font-semibold text-text mb-2">{t.skills.aiContext}</h3>
              <pre className="text-xs text-text-secondary font-mono bg-surface rounded-lg p-3 overflow-x-auto max-h-40 whitespace-pre-wrap">
                {skill.aiContext}
              </pre>
            </div>
          )}

          <Button variant="secondary" onClick={onClose} className="w-full">
            {t.common.close}
          </Button>
        </div>
      </div>
    </div>
  );
}

export function Skills() {
  const t = useT();
  const { data: skills, isLoading, error, refetch } = useSkills();
  const [search, setSearch] = useState('');
  const [categoryFilter, setCategoryFilter] = useState<SkillCategory | 'ALL'>('ALL');
  const [selectedSkill, setSelectedSkill] = useState<Skill | null>(null);

  const filtered = skills?.filter((s) => {
    const matchSearch = s.name.toLowerCase().includes(search.toLowerCase()) ||
      s.description.toLowerCase().includes(search.toLowerCase());
    const matchCategory = categoryFilter === 'ALL' || s.category === categoryFilter;
    return matchSearch && matchCategory;
  }) ?? [];

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-text">{t.skills.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.skills.subtitle}</p>
      </div>

      {/* Filters */}
      <div className="flex items-center gap-4 flex-wrap">
        <div className="max-w-sm flex-1">
          <Input
            placeholder={t.common.search}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            icon={<Search className="w-4 h-4 text-text-tertiary" />}
          />
        </div>
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => setCategoryFilter('ALL')}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
              categoryFilter === 'ALL'
                ? 'bg-accent text-text-inverse'
                : 'text-text-secondary hover:text-text hover:bg-surface-raised'
            }`}
          >
            {t.common.all}
          </button>
          {SKILL_CATEGORIES.map((cat) => (
            <button
              key={cat}
              onClick={() => setCategoryFilter(cat)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                categoryFilter === cat
                  ? 'bg-accent text-text-inverse'
                  : 'text-text-secondary hover:text-text hover:bg-surface-raised'
              }`}
            >
              {t.skills.categories[cat]}
            </button>
          ))}
        </div>
      </div>

      {/* Skills grid */}
      {filtered.length === 0 ? (
        <EmptyState
          icon={<Zap className="w-8 h-8 text-text-tertiary" />}
          title={search || categoryFilter !== 'ALL' ? t.common.noResults : t.skills.title}
          description={t.skills.noSkills}
        />
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 animate-stagger">
          {filtered.map((skill) => (
            <SkillCard key={skill.id} skill={skill} onSelect={setSelectedSkill} />
          ))}
        </div>
      )}

      {/* Skill detail slide-over */}
      {selectedSkill && (
        <SkillDetail skill={selectedSkill} onClose={() => setSelectedSkill(null)} />
      )}
    </div>
  );
}
