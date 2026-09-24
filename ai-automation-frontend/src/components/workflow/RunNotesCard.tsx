import { useRef } from 'react';
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

/**
 * Notes on a run: saved with the button (with a confirmation) or when the focus leaves the notes.
 * Only typed text that differs from the saved notes is saved, and each text is sent once — a click
 * on the button does not also save through the field losing its focus.
 */
export function RunNotesCard({ runId, savedNotes, draft, onDraftChange }: RunNotesCardProps) {
  const t = useT();
  const toast = useToast();
  const updateNotes = useUpdateRunNotes();
  // The text of the last request, so the button and leaving the field never send it twice
  const sent = useRef<string | null>(null);
  // Nothing typed (draft null) is no change: saving it would write '' over the saved notes
  const changed = draft !== null && draft !== savedNotes;

  const save = (confirm: boolean) => {
    if (draft === null || draft === savedNotes || draft === sent.current) return;
    sent.current = draft;
    updateNotes.mutate({ runId, notes: draft }, {
      onSuccess: () => { if (confirm) toast.success(t.workflows.execution.notesSaved); },
      onError: () => { sent.current = null; },
    });
  };

  return (
    <Card
      // Leaving the notes saves; moving between the field and the button does not
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) save(false);
      }}
    >
      <CardHeader
        title={t.workflows.execution.notes}
        action={
          changed ? (
            <Button
              size="sm"
              variant="ghost"
              icon={<Save className="w-3.5 h-3.5" />}
              // A mouse click keeps the focus in the field (some browsers never focus a button)
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => save(true)}
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
      />
    </Card>
  );
}
