import { useState } from 'react';
import { X, Search, Download, Check, Zap, ArrowLeft } from 'lucide-react';
import { Button, Input, Badge } from '../ui';
import { skillCatalog, type CatalogSkill } from '../../data/skillCatalog';
import { CATEGORY_ICONS, CATEGORY_COLORS, SKILL_CATEGORIES } from '../../data/skillConstants';
import { useImportSkill } from '../../hooks/useSkills';
import { useT } from '../../i18n';
import type { Skill, SkillCategory } from '../../types';

interface SkillCatalogProps {
  installedSkills: Skill[];
  onClose: () => void;
}

function CatalogCard({
  skill,
  isInstalled,
  onInstall,
  onSelect,
  installing,
}: {
  skill: CatalogSkill;
  isInstalled: boolean;
  onInstall: () => void;
  onSelect: () => void;
  installing: boolean;
}) {
  const t = useT();

  return (
    <div className="bg-surface border border-border rounded-2xl p-5 hover:border-border-strong transition-all group">
      <div className="cursor-pointer" onClick={onSelect}>
        <div className="flex items-start gap-3 mb-3">
          <div className={`p-2.5 rounded-xl bg-surface-raised ${CATEGORY_COLORS[skill.category]}`}>
            {CATEGORY_ICONS[skill.category]}
          </div>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <span className="text-lg">{skill.icon}</span>
              <h3 className="font-semibold text-text truncate">{skill.name}</h3>
            </div>
            <p className="text-xs text-text-tertiary mt-0.5">
              {t.skills.categories[skill.category]}
            </p>
          </div>
        </div>
        <p className="text-sm text-text-secondary line-clamp-2 mb-3">{skill.description}</p>
        <div className="flex items-center gap-2 text-xs text-text-tertiary mb-3">
          <Zap className="w-3 h-3" />
          <span>{skill.capabilities.length} {t.skills.capabilities.toLowerCase()}</span>
          <span className="font-mono">v{skill.version}</span>
        </div>
      </div>
      <div>
        {isInstalled ? (
          <Badge variant="success">
            <span className="flex items-center gap-1">
              <Check className="w-3 h-3" />
              {t.skills.installed}
            </span>
          </Badge>
        ) : (
          <Button
            size="sm"
            icon={<Download className="w-3.5 h-3.5" />}
            onClick={(e) => {
              e.stopPropagation();
              onInstall();
            }}
            loading={installing}
            disabled={installing}
          >
            {installing ? t.skills.installing : t.skills.install}
          </Button>
        )}
      </div>
    </div>
  );
}

function CatalogDetail({
  skill,
  isInstalled,
  installing,
  onInstall,
  onBack,
}: {
  skill: CatalogSkill;
  isInstalled: boolean;
  installing: boolean;
  onInstall: () => void;
  onBack: () => void;
}) {
  const t = useT();

  return (
    <div className="space-y-6">
      <button
        onClick={onBack}
        className="flex items-center gap-2 text-sm text-text-secondary hover:text-text transition-colors"
      >
        <ArrowLeft className="w-4 h-4" />
        {t.common.back}
      </button>

      <div className="flex items-start gap-4">
        <div className={`p-3 rounded-xl bg-surface-raised ${CATEGORY_COLORS[skill.category]}`}>
          {CATEGORY_ICONS[skill.category]}
        </div>
        <div className="flex-1">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-2xl">{skill.icon}</span>
            <h2 className="text-xl font-bold text-text">{skill.name}</h2>
          </div>
          <div className="flex items-center gap-2">
            <Badge variant="accent">{t.skills.categories[skill.category]}</Badge>
            <span className="text-xs text-text-tertiary font-mono">v{skill.version}</span>
          </div>
        </div>
        {isInstalled ? (
          <Badge variant="success">
            <span className="flex items-center gap-1">
              <Check className="w-3 h-3" />
              {t.skills.installed}
            </span>
          </Badge>
        ) : (
          <Button
            icon={<Download className="w-4 h-4" />}
            onClick={onInstall}
            loading={installing}
            disabled={installing}
          >
            {installing ? t.skills.installing : t.skills.install}
          </Button>
        )}
      </div>

      <p className="text-sm text-text-secondary">{skill.description}</p>

      {/* Capabilities */}
      <div>
        <h3 className="text-sm font-semibold text-text mb-3">{t.skills.capabilities}</h3>
        <div className="space-y-3">
          {skill.capabilities.map((cap) => (
            <div key={cap.name} className="p-3 rounded-lg bg-surface-raised border border-border">
              <div className="flex items-center gap-2 mb-1">
                <Zap className="w-3.5 h-3.5 text-accent" />
                <span className="text-sm font-medium text-text">{cap.name}</span>
                {cap.async && <Badge variant="warning">async</Badge>}
              </div>
              <p className="text-xs text-text-secondary">{cap.description}</p>
            </div>
          ))}
        </div>
      </div>

      {/* AI Context Preview */}
      <div>
        <h3 className="text-sm font-semibold text-text mb-2">{t.skills.previewAiContext}</h3>
        <pre className="text-xs text-text-secondary font-mono bg-surface-raised rounded-lg p-4 overflow-x-auto max-h-48 whitespace-pre-wrap border border-border">
          {skill.aiContext}
        </pre>
      </div>
    </div>
  );
}

export function SkillCatalog({ installedSkills, onClose }: SkillCatalogProps) {
  const t = useT();
  const [search, setSearch] = useState('');
  const [categoryFilter, setCategoryFilter] = useState<SkillCategory | 'ALL'>('ALL');
  const [selectedSkill, setSelectedSkill] = useState<CatalogSkill | null>(null);
  const [installingName, setInstallingName] = useState<string | null>(null);
  const importSkill = useImportSkill();

  const isInstalled = (skill: CatalogSkill) =>
    installedSkills.some((s) => s.name === skill.name && s.version === skill.version);

  const handleInstall = (skill: CatalogSkill) => {
    setInstallingName(skill.name);
    importSkill.mutate(skill, {
      onSettled: () => setInstallingName(null),
    });
  };

  const filtered = skillCatalog.filter((s) => {
    const matchSearch =
      s.name.toLowerCase().includes(search.toLowerCase()) ||
      s.description.toLowerCase().includes(search.toLowerCase());
    const matchCategory = categoryFilter === 'ALL' || s.category === categoryFilter;
    return matchSearch && matchCategory;
  });

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-background/95 backdrop-blur-sm animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between p-6 border-b border-border">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.skills.catalog}</h1>
          <p className="text-sm text-text-secondary mt-0.5">{t.skills.catalogSubtitle}</p>
        </div>
        <button
          onClick={onClose}
          className="p-2 rounded-lg hover:bg-surface-raised text-text-secondary hover:text-text transition-colors"
        >
          <X className="w-5 h-5" />
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-6">
        {selectedSkill ? (
          <div className="max-w-2xl mx-auto">
            <CatalogDetail
              skill={selectedSkill}
              isInstalled={isInstalled(selectedSkill)}
              installing={installingName === selectedSkill.name}
              onInstall={() => handleInstall(selectedSkill)}
              onBack={() => setSelectedSkill(null)}
            />
          </div>
        ) : (
          <>
            {/* Filters */}
            <div className="flex items-center gap-4 flex-wrap mb-6">
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

            {/* Grid */}
            {filtered.length === 0 ? (
              <div className="text-center py-16 text-text-tertiary">
                <p>{t.skills.catalogEmpty}</p>
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
                {filtered.map((skill) => (
                  <CatalogCard
                    key={skill.name}
                    skill={skill}
                    isInstalled={isInstalled(skill)}
                    onInstall={() => handleInstall(skill)}
                    onSelect={() => setSelectedSkill(skill)}
                    installing={installingName === skill.name}
                  />
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
