import { useState } from 'react';
import { Brain, Sparkles } from 'lucide-react';
import { Card, CardHeader, Button, Modal, ModalFooter } from '../ui';
import { MonacoEditor } from '../shared/MonacoEditor';
import { useUpdateProject, useGenerateProjectContext } from '../../hooks/useProjects';
import { useT } from '../../i18n';
import type { Project } from '../../types';

/**
 * The project's AI context and its editor: written by hand or drafted by the AI
 * (`generate-context`), stored only with "Save".
 */
export function ProjectAiContextCard({ project }: { project: Project }) {
  const t = useT();
  const updateProject = useUpdateProject();
  const generateContext = useGenerateProjectContext();
  const [showAiContext, setShowAiContext] = useState(false);
  const [editAiContext, setEditAiContext] = useState('');

  const openAiContext = () => {
    setEditAiContext(project.aiContext ?? '');
    setShowAiContext(true);
  };

  const handleSaveAiContext = () => {
    updateProject.mutate(
      { id: project.id, data: { aiContext: editAiContext } },
      { onSuccess: () => setShowAiContext(false) }
    );
  };

  const handleGenerate = () => {
    generateContext.mutate(project.id, {
      onSuccess: (data) => {
        setEditAiContext(data.context);
      },
    });
  };

  return (
    <>
      <Card>
        <CardHeader
          title={t.projects.aiContext}
          action={
            <Button
              size="sm"
              variant="secondary"
              icon={<Brain className="w-4 h-4" />}
              onClick={openAiContext}
            >
              {t.projects.editAiContext}
            </Button>
          }
        />
        {project.aiContext ? (
          <pre className="text-sm text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded-lg p-3 max-h-48 overflow-y-auto">
            {project.aiContext}
          </pre>
        ) : (
          <p className="text-sm text-text-tertiary">{t.projects.noAiContext}</p>
        )}
      </Card>

      <Modal
        open={showAiContext}
        onClose={() => setShowAiContext(false)}
        title={t.projects.editAiContext}
        size="lg"
        footer={
          <ModalFooter
            onCancel={() => setShowAiContext(false)}
            onAction={handleSaveAiContext}
            cancelLabel={t.common.cancel}
            actionLabel={t.common.save}
            actionLoading={updateProject.isPending}
          />
        }
      >
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <p className="text-sm text-text-secondary">{t.projects.aiContextHint}</p>
            <Button
              size="sm"
              variant="secondary"
              icon={<Sparkles className="w-3.5 h-3.5" />}
              onClick={handleGenerate}
              loading={generateContext.isPending}
            >
              {generateContext.isPending ? t.projects.generating : t.projects.generateAiContext}
            </Button>
          </div>
          <MonacoEditor
            value={editAiContext}
            onChange={setEditAiContext}
            language="markdown"
            height="400px"
            prose
          />
        </div>
      </Modal>
    </>
  );
}
