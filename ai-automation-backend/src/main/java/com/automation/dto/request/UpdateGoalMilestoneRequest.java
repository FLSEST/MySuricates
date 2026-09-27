package com.automation.dto.request;

/**
 * Teilaenderung eines Meilensteins (TODO 259f4f66): {@code null} laesst ein Feld stehen.
 * {@code dueDate = ""} entfernt das Datum (TODO c60bf914). {@code projectId} wechselt das Projekt –
 * nur solange dem Teilziel keine TODOs zugeordnet sind, sonst 400.
 */
public record UpdateGoalMilestoneRequest(
        String title,
        String dueDate,
        String projectId
) {
    /** Form ohne Projekt – fuer Aufrufer von vorher. */
    public UpdateGoalMilestoneRequest(String title, String dueDate) {
        this(title, dueDate, null);
    }
}
