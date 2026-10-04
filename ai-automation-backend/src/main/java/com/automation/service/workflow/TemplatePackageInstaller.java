package com.automation.service.workflow;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.TemplateKind;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowTemplate;
import com.automation.util.LocalizedJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Creates a workflow from a template on the server — together with the blueprints its sub-workflow
 * blocks call (TODO 093d2f11).
 *
 * <p>A shipped blueprint cannot know the id a called workflow will get. So its {@code sub-workflow}
 * block names the called blueprint instead ({@code "workflow_id": "blueprint-…"}), and installing
 * creates every named blueprint first, in the same project, then the template itself with each such
 * reference replaced by the new id — the way the chain template installation creates all workflows
 * before it wires them. {@code BlueprintGateTest} pins that every such reference names a shipped
 * blueprint that calls no further one.
 *
 * <p>A template without such a reference is created exactly as the client would create it; the
 * endpoint is therefore safe for every template.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplatePackageInstaller {

    /** Prefix of a blueprint id; a {@code workflow_id} with it names a blueprint, not a workflow. */
    public static final String BLUEPRINT_PREFIX = "blueprint-";

    private static final ObjectMapper COPY = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final WorkflowTemplateService templateService;
    private final WorkflowService workflowService;

    /**
     * @param workflow the workflow created from the template
     * @param parts    the workflows created for its blueprint references, in the order they are called
     */
    public record Installed(Workflow workflow, List<Workflow> parts) {
    }

    /**
     * @param name        name of the new workflow; blank → the template's name in {@code locale}
     * @param description blank → the template's description in {@code locale}
     * @param locale      {@code de}/{@code en}: language of the steps and names; without it English
     * @param metadata    metadata of the new workflow (e.g. {@code autoTodo}), as on a plain create; the parts get none
     */
    @Transactional
    public Installed install(String templateId, String projectId, String name, String description,
                             String locale, Map<String, Object> metadata) {
        String lang = LocalizedJson.normalize(locale);
        WorkflowTemplate template = templateService.findById(templateId);
        List<BlockDefinition> blocks = blocksOf(template, lang);

        Map<String, Workflow> created = new LinkedHashMap<>();
        for (String reference : blueprintReferences(blocks)) {
            WorkflowTemplate part = templateService.findById(reference);
            if (part.getKind() != TemplateKind.BLUEPRINT) {
                continue;
            }
            Workflow partWorkflow = create(part, projectId, LocalizedJson.pick(part.getName(), lang),
                    LocalizedJson.pick(part.getDescription(), lang), blocksOf(part, lang), lang, null);
            created.put(reference, partWorkflow);
            log.info("Created part workflow '{}' ({}) for template '{}'", partWorkflow.getName(), reference, templateId);
        }
        for (BlockDefinition block : blocks) {
            if (isSubWorkflow(block) && created.get(workflowIdOf(block)) instanceof Workflow part) {
                Map<String, Object> config = new HashMap<>(block.getConfig());
                config.put("workflow_id", part.getId());
                block.setConfig(config);
            }
        }

        Workflow workflow = create(template, projectId,
                isBlank(name) ? LocalizedJson.pick(template.getName(), lang) : name.strip(),
                isBlank(description) ? LocalizedJson.pick(template.getDescription(), lang) : description,
                blocks, lang, metadata);
        return new Installed(workflow, List.copyOf(created.values()));
    }

    /**
     * Ids of the blueprints the top-level sub-workflow blocks of {@code blocks} name, each once and in
     * call order. Only top-level blocks: a switch case cannot run a sub-workflow.
     */
    public static Set<String> blueprintReferences(List<BlockDefinition> blocks) {
        Set<String> references = new LinkedHashSet<>();
        if (blocks != null) {
            for (BlockDefinition block : blocks) {
                if (isSubWorkflow(block) && workflowIdOf(block) instanceof String id && id.startsWith(BLUEPRINT_PREFIX)) {
                    references.add(id);
                }
            }
        }
        return references;
    }

    private Workflow create(WorkflowTemplate template, String projectId, String name, String description,
                            List<BlockDefinition> blocks, String lang, Map<String, Object> metadata) {
        return workflowService.create(new CreateWorkflowRequest(
                projectId,
                Objects.requireNonNullElse(name, template.getId()),
                description,
                template.getIcon(),
                blocks,
                List.of(),
                LocalizedJson.instructionsFor(template.getI18nSource(), lang, template.getInstructions()),
                template.getModel(),
                null,
                template.getId(),
                metadata,
                null,
                null
        ), ChangeSource.TEMPLATE_INSTALL);
    }

    /**
     * The template's blocks in {@code lang}, always as a fresh copy: without a localized source
     * {@link LocalizedJson#blocksFor} hands back the entity's own list, and wiring a reference into
     * that would change the stored template.
     */
    private static List<BlockDefinition> blocksOf(WorkflowTemplate template, String lang) {
        List<BlockDefinition> blocks = LocalizedJson.blocksFor(template.getI18nSource(), lang, template.getBlocks());
        return new ArrayList<>(COPY.convertValue(blocks == null ? List.of() : blocks,
                new TypeReference<List<BlockDefinition>>() {}));
    }

    private static boolean isSubWorkflow(BlockDefinition block) {
        return block != null && "sub-workflow".equals(block.getType()) && block.getConfig() != null;
    }

    private static Object workflowIdOf(BlockDefinition block) {
        return block.getConfig().get("workflow_id");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
