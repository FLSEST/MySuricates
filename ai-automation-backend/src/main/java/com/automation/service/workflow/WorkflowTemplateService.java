package com.automation.service.workflow;

import com.automation.dto.request.CreateWorkflowTemplateRequest;
import com.automation.dto.request.UpdateWorkflowTemplateRequest;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowTemplate;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowTemplateService {

    private final WorkflowTemplateRepository templateRepository;
    private final WorkflowService workflowService;

    @Transactional(readOnly = true)
    public List<WorkflowTemplate> findAll() {
        return templateRepository.findAllByOrderBySortOrderAsc();
    }

    @Transactional(readOnly = true)
    public WorkflowTemplate findById(String id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTemplate", id));
    }

    @Transactional
    public WorkflowTemplate create(CreateWorkflowTemplateRequest request) {
        WorkflowTemplate template = WorkflowTemplate.builder()
                .name(new HashMap<>(request.name()))
                .description(request.description() != null ? new HashMap<>(request.description()) : new HashMap<>())
                .icon(request.icon())
                .category(request.category())
                .blocks(request.blocks())
                .instructions(request.instructions())
                .model(request.model())
                .builtIn(false)
                .build();

        WorkflowTemplate saved = templateRepository.save(template);
        log.info("Created workflow template: {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public WorkflowTemplate update(String id, UpdateWorkflowTemplateRequest request) {
        WorkflowTemplate template = findById(id);

        if (request.name() != null) {
            template.setName(new HashMap<>(request.name()));
        }
        if (request.description() != null) {
            template.setDescription(new HashMap<>(request.description()));
        }
        if (request.icon() != null) {
            template.setIcon(request.icon());
        }
        if (request.category() != null) {
            template.setCategory(request.category());
        }
        if (request.blocks() != null) {
            template.setBlocks(request.blocks());
        }
        if (request.instructions() != null) {
            template.setInstructions(request.instructions());
        }
        if (request.blocks() != null || request.instructions() != null) {
            // An edit is single-language; a stale German variant must not survive it.
            template.setI18nSource(null);
        }
        if (request.model() != null) {
            template.setModel(request.model());
        }
        if (request.sortOrder() != null) {
            template.setSortOrder(request.sortOrder());
        }

        WorkflowTemplate saved = templateRepository.save(template);
        log.info("Updated workflow template: {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(String id) {
        WorkflowTemplate template = findById(id);
        if (template.isBuiltIn()) {
            throw new IllegalStateException("Cannot delete built-in templates");
        }
        templateRepository.delete(template);
        log.info("Deleted workflow template: {} ({})", template.getName(), template.getId());
    }

    @Transactional
    public WorkflowTemplate createFromWorkflow(String workflowId) {
        Workflow workflow = workflowService.findById(workflowId);

        WorkflowTemplate template = WorkflowTemplate.builder()
                .name(Map.of("de", workflow.getName(), "en", workflow.getName()))
                .description(workflow.getDescription() != null
                        ? Map.of("de", workflow.getDescription(), "en", workflow.getDescription())
                        : new HashMap<>())
                .icon(workflow.getIcon())
                .blocks(workflow.getBlocks())
                .instructions(workflow.getInstructions())
                .model(workflow.getModel())
                .builtIn(false)
                .build();

        WorkflowTemplate saved = templateRepository.save(template);
        log.info("Created template from workflow: {} → {} ({})",
                workflow.getName(), saved.getName(), saved.getId());
        return saved;
    }
}
