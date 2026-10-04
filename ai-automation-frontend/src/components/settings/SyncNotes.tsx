/** What a sync leaves behind on purpose (Prod: references to unsealed workflows, 84a78bb7) — server text. */
export function SyncNotes({ notes, title }: { notes?: string[]; title: string }) {
  if (!notes || notes.length === 0) return null;
  return (
    <div className="rounded-md border border-warning/30 bg-warning/10 px-3 py-2 text-xs space-y-1" data-testid="sync-notes">
      <p className="font-medium text-warning">{title}</p>
      {notes.map((note, i) => (
        <p key={i} className="text-text-secondary">{note}</p>
      ))}
    </div>
  );
}
