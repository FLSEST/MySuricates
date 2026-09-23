package com.automation.service.chain;

import com.automation.dto.request.ChainEntryRequest;
import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.entity.ChangeSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.entity.WorkflowTemplate;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import com.automation.util.LocalizedJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChainTemplateService {

    private final WorkflowTemplateService workflowTemplateService;
    private final WorkflowService workflowService;
    private final ChainService chainService;

    /**
     * Install creates every workflow from a shipped built-in template, so each entry has to name one —
     * pinned by {@code ChainTemplateServiceTest}. A chain over pool entries does not exist; the SaaS
     * factory chain left together with its templates (TODO 7ddacd1d).
     */
    private static final List<BuiltInChainTemplate> TEMPLATES = List.of(
            new BuiltInChainTemplate(
                    "ci-cd-pipeline-chain",
                    Map.of("de", "CI/CD Pipeline", "en", "CI/CD Pipeline"),
                    Map.of("de", "Code-Qualitätsprüfung gefolgt von automatischer Deployment-Pipeline",
                            "en", "Code quality check followed by automated deployment pipeline"),
                    "🚀",
                    List.of(
                            new BuiltInChainTemplate.Entry("code-quality",
                                    Map.of("de", "Code-Qualität prüfen", "en", "Check code quality")),
                            new BuiltInChainTemplate.Entry("deploy-pipeline",
                                    Map.of("de", "Deployment ausführen", "en", "Run deployment"))
                    )
            ),
            new BuiltInChainTemplate(
                    "security-audit-chain",
                    Map.of("de", "Security-Audit-Pipeline", "en", "Security Audit Pipeline"),
                    Map.of("de", "Secret-Scanner + Container-Vulnerabilities + automatische Fixes und PR",
                            "en", "Secret scanning, container vulnerability scan, and automated fix PR"),
                    "🔐",
                    List.of(
                            new BuiltInChainTemplate.Entry("secret-scanner",
                                    Map.of("de", "Secrets scannen", "en", "Scan for secrets")),
                            new BuiltInChainTemplate.Entry("container-vuln-scan",
                                    Map.of("de", "Container-Vulnerabilities prüfen", "en", "Check container vulnerabilities")),
                            new BuiltInChainTemplate.Entry("audit-fix-pr-pipeline",
                                    Map.of("de", "Fixes und PR erstellen", "en", "Create fixes and PR"))
                    )
            ),
            new BuiltInChainTemplate(
                    "maintenance-check-chain",
                    Map.of("de", "Maintenance-Check", "en", "Maintenance Check"),
                    Map.of("de", "Dependency-Updates prüfen und Test-Coverage-Lücken identifizieren",
                            "en", "Check dependency updates and identify test coverage gaps"),
                    "🔧",
                    List.of(
                            new BuiltInChainTemplate.Entry("dependency-update-check",
                                    Map.of("de", "Dependencies prüfen", "en", "Check dependencies")),
                            new BuiltInChainTemplate.Entry("test-coverage-gaps",
                                    Map.of("de", "Test-Coverage analysieren", "en", "Analyze test coverage"))
                    )
            ),
            new BuiltInChainTemplate(
                    "code-health-chain",
                    Map.of("de", "Code-Gesundheit", "en", "Code Health"),
                    Map.of("de", "Toten Code finden + Architektur-Schulden + Codebase-Scorecard",
                            "en", "Dead code detection, architecture debt analysis, and codebase health scorecard"),
                    "🩺",
                    List.of(
                            new BuiltInChainTemplate.Entry("dead-code-detector",
                                    Map.of("de", "Toten Code finden", "en", "Find dead code")),
                            new BuiltInChainTemplate.Entry("architecture-debt-radar",
                                    Map.of("de", "Architektur-Schulden analysieren", "en", "Analyze architecture debt")),
                            new BuiltInChainTemplate.Entry("codebase-health-scorecard",
                                    Map.of("de", "Codebase-Scorecard erstellen", "en", "Generate codebase scorecard"))
                    )
            ),
            new BuiltInChainTemplate(
                    "team-sprint-chain",
                    Map.of("de", "Team-Sprint-Support", "en", "Team Sprint Support"),
                    Map.of("de", "Standup-Report aus Git + Sprint-Retrospektive",
                            "en", "Generate standup report from git and run sprint retrospective"),
                    "👥",
                    List.of(
                            new BuiltInChainTemplate.Entry("standup-from-git",
                                    Map.of("de", "Standup-Report generieren", "en", "Generate standup report")),
                            new BuiltInChainTemplate.Entry("sprint-retrospective",
                                    Map.of("de", "Sprint-Retrospektive durchführen", "en", "Run sprint retrospective"))
                    )
            )
    );

    public List<BuiltInChainTemplate> findAll() {
        return TEMPLATES;
    }

    public BuiltInChainTemplate findById(String id) {
        return TEMPLATES.stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Chain template not found: " + id));
    }

    @Transactional
    public WorkflowChain install(String templateId, String projectId, String chainName) {
        return install(templateId, projectId, chainName, null);
    }

    /**
     * @param locale {@code de}/{@code en}: language of names, labels and — for bilingual templates —
     *               the steps; without it German, as before (TODO a129a661)
     */
    @Transactional
    public WorkflowChain install(String templateId, String projectId, String chainName, String locale) {
        String lang = locale == null || locale.isBlank() ? "de" : LocalizedJson.normalize(locale);
        BuiltInChainTemplate template = findById(templateId);
        List<ChainEntryRequest> entries = new ArrayList<>();

        for (int i = 0; i < template.entries().size(); i++) {
            BuiltInChainTemplate.Entry entry = template.entries().get(i);
            WorkflowTemplate wfTemplate = workflowTemplateService.findById(entry.templateId());

            String wfName = LocalizedJson.pick(wfTemplate.getName(), lang);

            Workflow workflow = workflowService.create(new CreateWorkflowRequest(
                    projectId,
                    wfName,
                    null,
                    wfTemplate.getIcon(),
                    LocalizedJson.blocksFor(wfTemplate.getI18nSource(), lang, wfTemplate.getBlocks()),
                    List.of(),
                    LocalizedJson.instructionsFor(wfTemplate.getI18nSource(), lang, wfTemplate.getInstructions()),
                    wfTemplate.getModel(),
                    null,
                    wfTemplate.getId(),
                    null,
                    null,
                    null
            ), ChangeSource.CHAIN_TEMPLATE);

            entries.add(new ChainEntryRequest(
                    workflow.getId(),
                    Objects.requireNonNullElse(LocalizedJson.pick(entry.label(), lang), ""),
                    i,
                    true,
                    Map.of()
            ));

            log.info("Created workflow '{}' (template: {}) for chain template '{}'",
                    workflow.getName(), entry.templateId(), templateId);
        }

        String resolvedName = (chainName != null && !chainName.isBlank())
                ? chainName
                : Objects.requireNonNullElse(LocalizedJson.pick(template.name(), lang), template.id());

        return chainService.create(new CreateChainRequest(
                resolvedName,
                Objects.requireNonNullElse(LocalizedJson.pick(template.description(), lang), ""),
                template.icon(),
                entries,
                true
        ));
    }
}
