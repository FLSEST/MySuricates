import { FileText } from 'lucide-react';
import { Card } from '../ui';
import { useT } from '../../i18n';
import { workflowTemplates, type WorkflowTemplate } from '../../data/workflowTemplates';

interface TemplateSelectorProps {
  onSelect: (template: WorkflowTemplate | null) => void;
}

export function TemplateSelector({ onSelect }: TemplateSelectorProps) {
  const t = useT();

  return (
    <div className="space-y-4">
      <div>
        <h3 className="text-lg font-semibold text-text">{t.templates.title}</h3>
        <p className="text-sm text-text-secondary mt-1">{t.templates.subtitle}</p>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {/* Blank option */}
        <Card
          className="cursor-pointer hover:border-border-strong transition-colors"
          onClick={() => onSelect(null)}
        >
          <div className="flex items-center gap-3 mb-2">
            <div className="p-2 rounded-lg bg-surface">
              <FileText className="w-5 h-5 text-text-tertiary" />
            </div>
            <h4 className="font-semibold text-text">{t.templates.blank}</h4>
          </div>
          <p className="text-xs text-text-secondary">{t.templates.blankDescription}</p>
        </Card>

        {/* Template options */}
        {workflowTemplates.map((tmpl) => (
          <Card
            key={tmpl.id}
            className="cursor-pointer hover:border-border-strong transition-colors"
            onClick={() => onSelect(tmpl)}
          >
            <div className="flex items-center gap-3 mb-2">
              <span className="text-2xl">{tmpl.icon}</span>
              <div>
                <h4 className="font-semibold text-text">
                  {t.templates[tmpl.nameKey as keyof typeof t.templates]}
                </h4>
                <span className="text-xs text-text-tertiary">
                  {tmpl.blocks.length} {t.workflows.blocks.toLowerCase()}
                </span>
              </div>
            </div>
            <p className="text-xs text-text-secondary">
              {t.templates[tmpl.descriptionKey as keyof typeof t.templates]}
            </p>
          </Card>
        ))}
      </div>
    </div>
  );
}
