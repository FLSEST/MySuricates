package com.automation.controller;

import com.automation.entity.Workflow;
import com.automation.service.project.DockerAccess;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.analyzer.AnalyzerFinding;
import com.automation.service.workflow.analyzer.InstanceFacts;
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
 * Deterministic check of workflow definitions for construction errors — on demand, never blocking
 * (TODO 182bd224). Saving is left untouched: the analyzer reports, it prevents nothing. Both endpoints add
 * the rules that depend on this instance, e.g. container blocks without a reachable Docker (TODO 0ad8853b).
 */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowAnalyzerController {

    private final WorkflowService workflowService;
    private final WorkflowAnalyzer analyzer;
    private final DockerAccess dockerAccess;

    /** Returns the findings of one workflow definition. */
    @GetMapping("/{id}/analyze")
    public ResponseEntity<AnalysisResponse> analyze(@PathVariable String id) {
        Workflow workflow = workflowService.findByIdEager(id);
        List<AnalyzerFinding> findings = analyzer.analyze(workflow, instanceFacts());
        return ResponseEntity.ok(new AnalysisResponse(workflow.getId(), workflow.getName(),
                findings.size(), countBySeverity(findings), findings));
    }

    /**
     * Checks all definitions in active projects.
     *
     * <p>The answer is condensed on purpose: only counts per workflow, plus the distribution over the
     * rules. The full findings come per workflow — a full output over hundreds of definitions would be a
     * megabyte nobody reads.
     *
     * @param minSeverity {@code HIGH} or {@code MEDIUM} hides lighter findings
     */
    @GetMapping("/analyze")
    public ResponseEntity<InventoryResponse> analyzeAll(
            @RequestParam(required = false) AnalyzerFinding.Severity minSeverity) {
        List<WorkflowSummary> affected = new ArrayList<>();
        Map<String, Integer> byRule = new LinkedHashMap<>();
        int checked = 0;
        int total = 0;
        InstanceFacts instance = instanceFacts();

        for (Workflow workflow : workflowService.findAll()) {
            checked++;
            List<AnalyzerFinding> findings = analyzer.analyze(workflow, instance).stream()
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

    /** One Docker probe per request, not per workflow. */
    private InstanceFacts instanceFacts() {
        DockerAccess.Status docker = dockerAccess.status();
        return new InstanceFacts(docker.reachable(), docker.hint(), docker.hintEn());
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
