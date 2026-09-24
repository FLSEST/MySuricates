import { Save } from 'lucide-react';
import { Card, CardHeader, Button } from '../ui';
import { useUpdateRunNotes } from '../../hooks/useWorkflows';
import { useToast } from '../../hooks/useToast';
import { useT } from '../../i18n';

interface RunNotesCardProps {
  runId: string;
  /** The notes as saved on the run (`metadata.notes`). */
  savedNotes: string;
  /**
   * Unsaved text, `null` while nothing was typed. The page holds it, so it survives the page
   * briefly showing an error or the spinner while the run is refetched.
   */
  draft: string | null;
  onDraftChange: (text: string) => void;
}

/** Notes on a run: saved with the button (with a confirmation) or when the field loses focus. */
export function RunNotesCard({ runId, savedNotes, draft, onDraftChange }: RunNotesCardProps) {
  const t = useT();
  const toast = useToast();
  const updateNotes = useUpdateRunNotes();

  return (
    <Card>
      <CardHeader
        title={t.workflows.execution.notes}
        action={
          (draft ?? '') !== savedNotes ? (
            <Button
              size="sm"
              variant="ghost"
              icon={<Save className="w-3.5 h-3.5" />}
              onClick={() => {
                updateNotes.mutate({ runId, notes: draft ?? '' }, {
                  onSuccess: () => toast.success(t.workflows.execution.notesSaved),
                });
              }}
              loading={updateNotes.isPending}
            >
              {t.common.save}
            </Button>
          ) : undefined
        }
      />
      <textarea
        className="w-full bg-surface border border-border rounded-lg p-3 text-sm text-text placeholder-text-tertiary resize-y min-h-[60px] focus:outline-none focus:ring-1 focus:ring-accent"
        placeholder={t.workflows.execution.notesPlaceholder}
        value={draft ?? savedNotes}
        onChange={(e) => onDraftChange(e.target.value)}
        onBlur={() => {
          if (draft !== null && draft !== savedNotes) {
            updateNotes.mutate({ runId, notes: draft });
          }
        }}
      />
    </Card>
  );
}
