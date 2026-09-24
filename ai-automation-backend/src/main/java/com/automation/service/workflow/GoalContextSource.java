package com.automation.service.workflow;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Liefert die Kontextvariablen eines Ziels ({@code goal_status}, {@code goal_guard_*}), wenn ein Lauf
 * {@code goal_id} traegt. Umgesetzt in {@code service.goal}: So braucht {@code service.workflow} keine
 * Kante zu {@code service.goal}, die sonst goal, todo, trigger, scheduler, chain, audit und mapper
 * zurueck in die grosse Zyklus-Komponente zoege (TODO d2844a58).
 */
public interface GoalContextSource {

    /** Freie Plaetze fuer Punkte der Kachel: {@code goal_guard_item_1} … {@code _5}. */
    int ITEM_SLOTS = 5;

    /** Alle Variablen, die gesetzt werden – bei einer Luecke jede mit dem Lueckenformat. */
    List<String> VARIABLES = Stream.concat(
            Stream.of("goal_status", "goal_guard_status", "goal_guard_title", "goal_guard_summary",
                    "goal_guard_progress", "goal_guard_overdue", "goal_guard_due_soon", "goal_guard_items"),
            IntStream.rangeClosed(1, ITEM_SLOTS).mapToObj(i -> "goal_guard_item_" + i)).toList();

    /** Name → Wert fuer das Ziel; wirft, wenn es das Ziel nicht gibt. */
    Map<String, String> variables(String goalId, String locale);
}
