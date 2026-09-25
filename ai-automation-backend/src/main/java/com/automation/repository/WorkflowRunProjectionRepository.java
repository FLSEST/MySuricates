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
 * Schlanke Projektionen von Laeufen, gebuendelt nach einer Liste von Lauf-Ids: fuer Leseseiten, die weder
 * Schritte noch Metadaten, Fehler oder Eingabekontext eines Laufs laden duerfen (pool=1). Nur Abfragen, keine
 * Schreibmethoden – geschrieben wird ueber {@link WorkflowRunRepository}, aus dem diese Abfragen stammen
 * (TODO 982abd1a: die Datei stand bei 299 von 300 erlaubten Zeilen).
 */
@org.springframework.stereotype.Repository
public interface WorkflowRunProjectionRepository extends Repository<WorkflowRun, String> {

    /**
     * Lauf-Id, Workflow-Id und der beim Start festgehaltene Workflow-Name je Lauf — ohne Schritte und
     * ohne Join auf den Workflow, in einer Abfrage. Für die Liste wartender Freigaben, die jede Seite pollt.
     */
    @Query("SELECT wr.id, wr.workflow.id, wr.workflowName FROM WorkflowRun wr WHERE wr.id IN :ids")
    List<Object[]> findWorkflowRefsByRunIds(@Param("ids") Collection<String> ids);

    /** Status, Zeiten und Workflow je Lauf in einer Abfrage, ohne Schritte (TODO c6ea8f5c). */
    @Query("SELECT new com.automation.dto.projection.RunStateRow(wr.id, wr.workflow.id, wr.workflowName, "
         + "wr.status, wr.startedAt, wr.completedAt) FROM WorkflowRun wr WHERE wr.id IN :ids")
    List<RunStateRow> findRunStates(@Param("ids") Collection<String> ids);

    /**
     * Herkunft je Lauf in einer Abfrage (TODO a3b89355), Spalten wie {@code RunOriginRow.fromColumns}: ohne
     * Schritte, Metadaten und Fehler, und vom Kontext nur der Eltern-Ausloeser. Den Rest — bei einem
     * Ketteneintrag die ungekuerzte previous_chain_output — liest SQLite, nach Java kommt er nicht.
     * <ul>
     *   <li>Nativ, weil JPQL den Kontext nur ganz kennt: durch den JsonMapConverter, der an ungueltigem JSON
     *       die ganze Seite scheitern liesse.</li>
     *   <li>{@code json_valid} vor {@code json_extract}: Das wirft sonst bei ungueltigem JSON ("malformed JSON").</li>
     *   <li>{@code COALESCE(CAST(… AS TEXT), '')}: Fuer einen Ausdruck ohne deklarierten Typ nimmt sqlite-jdbc
     *       den Typ des Werts der ersten Zeile. Ist der NULL, meldet der Treiber NUMERIC, und Hibernate liest
     *       die Spalte als BigDecimal ("Bad value for type BigDecimal : SCHEDULE"). Nie NULL, immer Text haelt
     *       sie beim Typ TEXT ({@code WorkflowRunProjectionRepositoryTest}).</li>
     * </ul>
     */
    @Query(value = "SELECT wr.id, wr.trigger_source, wr.triggered_by_schedule_id, wr.chain_run_id, wr.definition_version_id, "
            + "COALESCE(CAST(CASE WHEN json_valid(wr.input_context) "
            + "THEN json_extract(wr.input_context, '$." + TriggerSource.PARENT_CONTEXT_KEY + "') END AS TEXT), '') "
            + "FROM workflow_runs wr WHERE wr.id IN (:ids)", nativeQuery = true)
    List<Object[]> findOriginColumns(@Param("ids") Collection<String> ids);

    /** {@link #findOriginColumns} als Zeilen — fuer die Herkunft der Audit-Reports. */
    default List<RunOriginRow> findOriginRows(Collection<String> ids) {
        return findOriginColumns(ids).stream().map(RunOriginRow::fromColumns).toList();
    }
}
