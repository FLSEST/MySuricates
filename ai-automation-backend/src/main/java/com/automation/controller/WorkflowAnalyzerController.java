package com.automation.controller;

import com.automation.entity.Workflow;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.analyzer.AnalyzerFinding;
import com.automation.service.workflow.analyzer.WorkflowAnalyzer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministische Baufehler-Prüfung von Workflow-Definitionen — auf Abruf, nie blockierend
 * (TODO 182bd224). Der Speicherweg bleibt unangetastet: Der Analyzer meldet, er verhindert nichts.
 */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowAnalyzerController {

    private final WorkflowService workflowService;
    private final WorkflowAnalyzer analyzer;

    /** Befunde einer Definition. */
    @GetMapping("/{id}/analyze")
    public ResponseEntity<AnalysisResponse> analyze(@PathVariable String id) {
        Workflow workflow = workflowService.findByIdEager(id);
        List<AnalyzerFinding> findings = analyzer.analyze(workflow);
        return ResponseEntity.ok(new AnalysisResponse(workflow.getId(), workflow.getName(),
                findings.size(), countBySeverity(findings), findings));
    }

    /**
     * Bestandsprüfung über alle Definitionen in aktiven Projekten.
     *
     * <p>Antwortet bewusst verdichtet: je Workflow nur Zahlen, dazu die Verteilung über die Regeln.
     * Die vollen Befunde holt man sich je Workflow — eine Vollausgabe über hunderte Definitionen
     * wäre ein Megabyte, das niemand liest.
     *
     * @param minSeverity {@code HIGH} oder {@code MEDIUM} blendet leichtere Befunde aus
     */
    @GetMapping("/analyze")
    public ResponseEntity<InventoryResponse> analyzeAll(
            @RequestParam(required = false) AnalyzerFinding.Severity minSeverity) {
        List<WorkflowSummary> affected = new ArrayList<>();
        Map<String, Integer> byRule = new LinkedHashMap<>();
        int checked = 0;
        int total = 0;

        for (Workflow workflow : workflowService.findAll()) {
            checked++;
            List<AnalyzerFinding> findings = analyzer.analyze(workflow).stream()
                    .filter(f -> minSeverity == null || f.severity().compareTo(minSeverity) <= 0)
                    .toList();
            if (findings.isEmpty()) continue;
            total += findings.size();
            findings.forEach(f -> byRule.merge(f.rule(), 1, Integer::sum));
            affected.add(new WorkflowSummary(workflow.getId(), workflow.getName(),
                    findings.size(), countBySeverity(findings)));
        }
        affected.sort((a, b) -> Integer.compare(b.findings(), a.findings()));
        return ResponseEntity.ok(new InventoryResponse(checked, affected.size(), total, byRule, affected));
    }

    private static Map<String, Integer> countBySeverity(List<AnalyzerFinding> findings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AnalyzerFinding.Severity severity : AnalyzerFinding.Severity.values()) {
            int n = (int) findings.stream().filter(f -> f.severity() == severity).count();
            if (n > 0) counts.put(severity.name(), n);
        }
        return counts;
    }

    public record AnalysisResponse(String workflowId, String workflowName, int findingCount,
                                   Map<String, Integer> bySeverity, List<AnalyzerFinding> findings) {}

    public record WorkflowSummary(String workflowId, String workflowName, int findings,
                                  Map<String, Integer> bySeverity) {}

    public record InventoryResponse(int workflowsChecked, int workflowsAffected, int findingsTotal,
                                    Map<String, Integer> byRule, List<WorkflowSummary> workflows) {}
}
