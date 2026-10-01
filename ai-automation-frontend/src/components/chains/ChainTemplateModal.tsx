import { useState } from 'react';
import { Card, Button, Input, Modal, Spinner } from '../ui';
import { useChainTemplates, useInstallChainTemplate } from '../../hooks/useChainTemplates';
import { useProjects } from '../../hooks/useProjects';
import { useT, useI18nStore } from '../../i18n';
import type { ChainTemplate } from '../../types';

export function ChainTemplateModal({ onClose }: { onClose: () => void }) {
  const t = useT();
  const locale = useI18nStore(s => s.locale);
  const { data: templates, isLoading } = useChainTemplates();
  const { data: projects } = useProjects();
  const installTemplate = useInstallChainTemplate();

  const [selectedTemplate, setSelectedTemplate] = useState<ChainTemplate | null>(null);
  const [projectId, setProjectId] = useState('');
  const [chainName, setChainName] = useState('');

  const activeProjects = (projects ?? []).filter(p => !p.archivedAt);

  const handleInstall = () => {
    if (!selectedTemplate || !projectId) return;
    installTemplate.mutate(
      { templateId: selectedTemplate.id, projectId, chainName: chainName || undefined },
      { onSuccess: () => onClose() }
    );
  };

  const resolveName = (names: Record<string, string>) =>
    names[locale] ?? names['de'] ?? names['en'] ?? Object.values(names)[0] ?? '';

  return (
    <Modal open onClose={onClose} title={t.chains.chainTemplates} size="lg">
      <p className="text-sm text-text-secondary mb-4">{t.chains.chainTemplatesSubtitle}</p>
      {isLoading ? (
        <div className="flex justify-center py-8"><Spinner text={t.common.loading} /></div>
      ) : (
        <div className="space-y-4">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 max-h-64 overflow-y-auto">
            {(templates ?? []).map(tmpl => (
              <Card
                key={tmpl.id}
                className={`cursor-pointer transition-all ${selectedTemplate?.id === tmpl.id ? 'ring-2 ring-accent' : 'hover:border-border-strong'}`}
                onClick={() => setSelectedTemplate(tmpl)}
              >
                <div className="flex items-start gap-3">
                  <span className="text-2xl">{tmpl.icon}</span>
                  <div className="min-w-0">
                    <p className="font-semibold text-text text-sm">{resolveName(tmpl.name)}</p>
                    <p className="text-xs text-text-secondary mt-0.5">{resolveName(tmpl.description)}</p>
                    <p className="text-xs text-text-tertiary mt-1">
                      {t.chains.installSteps.replace('{count}', String(tmpl.entries.length))}
                    </p>
                  </div>
                </div>
              </Card>
            ))}
          </div>

          {selectedTemplate && (
            <div className="space-y-3 pt-2 border-t border-border">
              <div>
                <label className="text-sm font-medium text-text-secondary mb-1 block">{t.chains.selectProject}</label>
                <select
                  value={projectId}
                  onChange={e => setProjectId(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg bg-surface border border-border text-text text-sm focus:outline-none focus:ring-2 focus:ring-accent"
                >
                  <option value="">— {t.chains.selectProject} —</option>
                  {activeProjects.map(p => (
                    <option key={p.id} value={p.id}>{p.name}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="text-sm font-medium text-text-secondary mb-1 block">{t.chains.chainName}</label>
                <Input
                  value={chainName}
                  onChange={e => setChainName(e.target.value)}
                  placeholder={t.chains.chainNamePlaceholder}
                />
              </div>
            </div>
          )}
        </div>
      )}
      <div className="flex justify-end gap-2 pt-4">
        <Button variant="ghost" onClick={onClose}>{t.common.cancel}</Button>
        <Button
          onClick={handleInstall}
          disabled={!selectedTemplate || !projectId || installTemplate.isPending}
        >
          {installTemplate.isPending ? t.common.loading : t.chains.installTemplate}
        </Button>
      </div>
    </Modal>
  );
}
