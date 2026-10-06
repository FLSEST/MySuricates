package com.automation.repository;

import com.automation.dto.projection.RunOriginRow;
import com.automation.dto.projection.RunStateRow;
import com.automation.entity.TriggerSource;
import com.automation.entity.WorkflowRun;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Lean projections of runs, bundled by a list of run ids: for read pages that must load neither
 * steps nor metadata, errors or input context of a run (pool=1). Queries only, no
 * write methods – writing goes through {@link WorkflowRunRepository}, from which these queries originate
 * (TODO 982abd1a: the file stood at 299 of 300 allowed lines).
 */
@org.springframework.stereotype.Repository
public interface WorkflowRunProjectionRepository extends Repository<WorkflowRun, String> {

    /**
     * Run id, workflow id and the workflow name recorded at start per run — without steps and
     * without a join on the workflow, in one query. For the list of waiting approvals, which every page polls.
     */
    @Query("SELECT wr.id, wr.workflow.id, wr.workflowName FROM WorkflowRun wr WHERE wr.id IN :ids")
    List<Object[]> findWorkflowRefsByRunIds(@Param("ids") Collection<String> ids);

    /** Status, times and workflow per run in one query, without steps (TODO c6ea8f5c). */
    @Query("SELECT new com.automation.dto.projection.RunStateRow(wr.id, wr.workflow.id, wr.workflowName, "
         + "wr.status, wr.startedAt, wr.completedAt) FROM WorkflowRun wr WHERE wr.id IN :ids")
    List<RunStateRow> findRunStates(@Param("ids") Collection<String> ids);

    /**
     * Origin per run in one query (TODO a3b89355), columns like {@code RunOriginRow.fromColumns}: without
     * steps, metadata and errors, and of the context only the parent trigger. The rest — for a
     * chain entry the untruncated previous_chain_output — is read by SQLite, it does not reach Java.
     * <ul>
     *   <li>Native, because JPQL only knows the context as a whole: through the JsonMapConverter, which on invalid JSON
     *       would make the whole page fail.</li>
     *   <li>{@code json_valid} before {@code json_extract}: otherwise that throws on invalid JSON ("malformed JSON").</li>
     *   <li>{@code COALESCE(CAST(… AS TEXT), '')}: for an expression without a declared type sqlite-jdbc
     *       takes the type of the first row's value. If that is NULL, the driver reports NUMERIC, and Hibernate reads
     *       the column as BigDecimal ("Bad value for type BigDecimal : SCHEDULE"). Never NULL, always text keeps
     *       it at type TEXT ({@code WorkflowRunProjectionRepositoryTest}).</li>
     * </ul>
     */
    @Query(value = "SELECT wr.id, wr.trigger_source, wr.triggered_by_schedule_id, wr.chain_run_id, wr.definition_version_id, "
            + "COALESCE(CAST(CASE WHEN json_valid(wr.input_context) "
            + "THEN json_extract(wr.input_context, '$." + TriggerSource.PARENT_CONTEXT_KEY + "') END AS TEXT), '') "
            + "FROM workflow_runs wr WHERE wr.id IN (:ids)", nativeQuery = true)
    List<Object[]> findOriginColumns(@Param("ids") Collection<String> ids);

    /** {@link #findOriginColumns} as rows — for the origin of the audit reports. */
    default List<RunOriginRow> findOriginRows(Collection<String> ids) {
        return findOriginColumns(ids).stream().map(RunOriginRow::fromColumns).toList();
    }
}
