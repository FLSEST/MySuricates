package com.automation.repository;

import com.automation.dto.projection.PriorityCount;
import com.automation.dto.projection.TodoHeadline;
import com.automation.entity.Todo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Lean summaries of TODOs for prompt contexts such as the assistant's (TODO f1074d2e): counted and
 * capped in the query, never the description blob (pool=1). Queries only – writing goes through
 * {@link TodoRepository}, which stands at the 300-line limit of {@code SourceSizeRatchetTest}.
 */
public interface TodoSummaryRepository extends Repository<Todo, String> {

    /** TODOs per priority among {@code statuses}; priorities without a TODO have no row. */
    @Query("SELECT new com.automation.dto.projection.PriorityCount(t.priority, COUNT(t)) FROM Todo t "
         + "WHERE t.status IN :statuses GROUP BY t.priority")
    List<PriorityCount> countByPriority(@Param("statuses") Collection<Todo.Status> statuses);

    /**
     * Title and priority of the TODOs among {@code statuses}, CRITICAL first, oldest first within a
     * priority; the caller caps the list via {@code pageable}.
     */
    @Query("SELECT new com.automation.dto.projection.TodoHeadline(t.title, t.priority) FROM Todo t "
         + "WHERE t.status IN :statuses "
         + "ORDER BY CASE t.priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 "
         + "WHEN 'MEDIUM' THEN 2 ELSE 3 END, t.createdAt ASC")
    List<TodoHeadline> findHeadlines(@Param("statuses") Collection<Todo.Status> statuses, Pageable pageable);
}
