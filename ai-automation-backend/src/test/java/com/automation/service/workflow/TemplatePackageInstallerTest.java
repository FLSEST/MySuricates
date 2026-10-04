package com.automation.service.workflow;

import com.automation.config.BuiltInTemplateLoader;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.TemplateKind;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowTemplate;
import com.automation.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TODO 093d2f11: a blueprint package installs parent and part and wires the part's id. */
@ExtendWith(MockitoExtension.class)
class TemplatePackageInstallerTest {

    private static final List<WorkflowTemplate> SHIPPED = new BuiltInTemplateLoader(new ObjectMapper()).loadAll();

    @Mock private WorkflowTemplateService templateService;
    @Mock private WorkflowService workflowService;
    @InjectMocks private TemplatePackageInstaller installer;

    private final List<CreateWorkflowRequest> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(templateService.findById(any())).thenAnswer(inv -> SHIPPED.stream()
                .filter(t -> t.getId().equals(inv.getArgument(0)))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTemplate", inv.getArgument(0))));
        lenient().when(workflowService.create(any(), eq(ChangeSource.TEMPLATE_INSTALL))).thenAnswer(inv -> {
            CreateWorkflowRequest request = inv.getArgument(0);
            created.add(request);
            return Workflow.builder().id("wf-" + created.size()).name(request.name()).build();
        });
    }

    @Test
    void shippedPackage_createsThePartFirst_andWiresItsIdIntoTheParent() {
        TemplatePackageInstaller.Installed installed = installer.install(
                "blueprint-sub-workflow", "p-1", null, null, "de", Map.of("autoTodo", Map.of("enabled", true)));

        assertThat(created).extracting(CreateWorkflowRequest::templateId)
                .containsExactly("blueprint-sub-workflow-part", "blueprint-sub-workflow");
        assertThat(created).allSatisfy(r -> assertThat(r.projectId()).isEqualTo("p-1"));

        CreateWorkflowRequest part = created.get(0);
        assertThat(part.name()).isEqualTo("Teil-Workflow: Eingabe prüfen");
        assertThat(part.metadata()).as("metadata gilt nur dem angelegten Workflow").isNull();
        assertThat(part.blocks()).extracting(BlockDefinition::getName).containsExactly("pruefen");

        CreateWorkflowRequest parent = created.get(1);
        assertThat(parent.name()).isEqualTo("Teil-Workflow aufrufen (Paket)");
        assertThat(parent.metadata()).containsKey("autoTodo");
        BlockDefinition call = parent.blocks().stream().filter(b -> "sub-workflow".equals(b.getType()))
                .findFirst().orElseThrow();
        assertThat(call.getConfig()).containsEntry("workflow_id", "wf-1");
        assertThat(call.getName()).isEqualTo("teil");

        assertThat(installed.workflow().getId()).isEqualTo("wf-2");
        assertThat(installed.parts()).extracting(Workflow::getId).containsExactly("wf-1");
    }

    @Test
    void wiringNeverTouchesTheStoredTemplate() {
        WorkflowTemplate parent = SHIPPED.stream().filter(t -> "blueprint-sub-workflow".equals(t.getId()))
                .findFirst().orElseThrow();

        installer.install("blueprint-sub-workflow", "p-1", "Mein Paket", "", "en", null);

        assertThat(created.get(1).name()).isEqualTo("Mein Paket");
        assertThat(created.get(1).description()).as("leere Beschreibung → die der Vorlage").startsWith("Package of two");
        assertThat(parent.getBlocks().get(1).getConfig()).containsEntry("workflow_id", "blueprint-sub-workflow-part");
    }

    @Test
    void templateWithoutReference_isCreatedAlone() {
        installer.install("blueprint-approval", "p-1", null, null, "en", null);

        verify(workflowService, times(1)).create(any(), eq(ChangeSource.TEMPLATE_INSTALL));
        assertThat(created.get(0).name()).isEqualTo("Approval before acting");
    }

    @Test
    void aWorkflowIdWithoutTheBlueprintPrefix_staysAsItIs() {
        WorkflowTemplate custom = template("custom", TemplateKind.TEMPLATE,
                subWorkflow("3f0c1d2e-0000-4000-8000-000000000000"));
        doReturn(custom).when(templateService).findById("custom");

        installer.install("custom", "p-1", "x", null, "en", null);

        assertThat(created).hasSize(1);
        assertThat(created.get(0).blocks().get(0).getConfig())
                .containsEntry("workflow_id", "3f0c1d2e-0000-4000-8000-000000000000");
    }

    @Test
    void aReferenceToAnUnknownBlueprint_failsBeforeAnythingIsCreated() {
        doReturn(template("custom", TemplateKind.TEMPLATE, subWorkflow("blueprint-does-not-exist")))
                .when(templateService).findById("custom");

        assertThatThrownBy(() -> installer.install("custom", "p-1", "x", null, "en", null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(created).isEmpty();
    }

    @Test
    void aReferenceToATaskTemplate_isNotWired() {
        WorkflowTemplate task = template("blueprint-lookalike", TemplateKind.TEMPLATE, List.of());
        doReturn(template("custom", TemplateKind.TEMPLATE, subWorkflow("blueprint-lookalike")))
                .when(templateService).findById("custom");
        doReturn(task).when(templateService).findById("blueprint-lookalike");

        installer.install("custom", "p-1", "x", null, "en", null);

        assertThat(created).hasSize(1);
        assertThat(created.get(0).blocks().get(0).getConfig()).containsEntry("workflow_id", "blueprint-lookalike");
    }

    @Test
    void references_areTopLevelBlueprintIds_eachOnce() {
        List<BlockDefinition> blocks = new ArrayList<>();
        blocks.addAll(subWorkflow("blueprint-a"));
        blocks.addAll(subWorkflow("blueprint-a"));
        blocks.addAll(subWorkflow("not-a-blueprint"));
        blocks.add(BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of("workflow_id", "blueprint-b"))).build());

        assertThat(TemplatePackageInstaller.blueprintReferences(blocks)).containsExactly("blueprint-a");
    }

    private static List<BlockDefinition> subWorkflow(String workflowId) {
        return List.of(BlockDefinition.builder().type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", workflowId))).build());
    }

    private static WorkflowTemplate template(String id, TemplateKind kind, List<BlockDefinition> blocks) {
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id(id).name(Map.of("en", id)).description(Map.of("en", id))
                .blocks(new ArrayList<>(blocks)).sortOrder(0).version("1.0.0").builtIn(false).build();
        template.setKind(kind);
        return template;
    }
}
