package com.automation.mapper;

import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.entity.BlockDefinition;
import com.automation.entity.WorkflowTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTemplateMapperTest {

    private WorkflowTemplateMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new WorkflowTemplateMapperImpl();
    }

    @Nested
    class ToResponse {

        @Test
        void mapsAllFields() {
            Instant now = Instant.now();
            WorkflowTemplate template = WorkflowTemplate.builder()
                    .id("tpl-1")
                    .name(Map.of("de", "Code-Review", "en", "Code Review"))
                    .description(Map.of("de", "Überprüft Code", "en", "Reviews code"))
                    .icon("🔍")
                    .category("development")
                    .blocks(List.of())
                    .instructions("Review carefully")
                    .model("claude-sonnet-4-6")
                    .builtIn(true)
                    .sortOrder(5)
                    .qualityStatus("VERIFIED")
                    .version("2.0.0")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            WorkflowTemplateResponse response = mapper.toResponse(template);

            assertThat(response.id()).isEqualTo("tpl-1");
            assertThat(response.name()).containsEntry("de", "Code-Review").containsEntry("en", "Code Review");
            assertThat(response.description()).containsEntry("de", "Überprüft Code");
            assertThat(response.icon()).isEqualTo("🔍");
            assertThat(response.category()).isEqualTo("development");
            assertThat(response.blocks()).isEmpty();
            assertThat(response.instructions()).isEqualTo("Review carefully");
            assertThat(response.model()).isEqualTo("claude-sonnet-4-6");
            assertThat(response.builtIn()).isTrue();
            assertThat(response.sortOrder()).isEqualTo(5);
            assertThat(response.qualityStatus()).isEqualTo("VERIFIED");
            assertThat(response.version()).isEqualTo("2.0.0");
            assertThat(response.createdAt()).isEqualTo(now);
            assertThat(response.updatedAt()).isEqualTo(now);
        }

        @Test
        void verifications_alwaysNull() {
            WorkflowTemplate template = WorkflowTemplate.builder()
                    .id("tpl-2")
                    .name(Map.of("en", "Test"))
                    .build();

            WorkflowTemplateResponse response = mapper.toResponse(template);

            assertThat(response.verifications()).isNull();
        }

        @Test
        void nullOptionalFields_mappedAsNull() {
            WorkflowTemplate template = WorkflowTemplate.builder()
                    .id("tpl-3")
                    .name(Map.of("en", "Minimal"))
                    .build();

            WorkflowTemplateResponse response = mapper.toResponse(template);

            assertThat(response.icon()).isNull();
            assertThat(response.category()).isNull();
            assertThat(response.instructions()).isNull();
            assertThat(response.model()).isNull();
            assertThat(response.createdAt()).isNull();
            assertThat(response.updatedAt()).isNull();
        }

        /** What the picker needs to hide what this instance cannot run (TODO 560d18dd). */
        @Test
        void namesTheModulesItsBlocksNeed_andWhetherItCallsAModel() {
            WorkflowTemplate skills = WorkflowTemplate.builder().id("tpl-4").name(Map.of("en", "Skills"))
                    .blocks(List.of(
                            BlockDefinition.builder().type("shell").config(Map.of("command", "ls")).build(),
                            BlockDefinition.builder().type("skill-capability").config(Map.of("capability", "x")).build(),
                            BlockDefinition.builder().type("integration").config(Map.of("provider", "github")).build()))
                    .build();
            WorkflowTemplate shellOnly = WorkflowTemplate.builder().id("tpl-5").name(Map.of("en", "Shell"))
                    .blocks(List.of(
                            BlockDefinition.builder().type("shell").config(Map.of("command", "ls")).build(),
                            BlockDefinition.builder().type("foreach")
                                    .config(Map.of("items", List.of("a"), "command", "echo {{ item }}",
                                            "execution_mode", "shell")).build()))
                    .build();

            WorkflowTemplateResponse needsModules = mapper.toResponse(skills);
            WorkflowTemplateResponse runsAnywhere = mapper.toResponse(shellOnly);

            assertThat(needsModules.requiredModules()).containsExactly("integrations", "skills");
            assertThat(needsModules.needsAi()).isTrue();
            assertThat(runsAnywhere.requiredModules()).isEmpty();
            assertThat(runsAnywhere.needsAi()).isFalse();
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        WorkflowTemplate t1 = WorkflowTemplate.builder().id("tpl-1").name(Map.of("en", "A")).build();
        WorkflowTemplate t2 = WorkflowTemplate.builder().id("tpl-2").name(Map.of("en", "B")).build();

        List<WorkflowTemplateResponse> responses = mapper.toResponseList(List.of(t1, t2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo("tpl-1");
        assertThat(responses.get(1).id()).isEqualTo("tpl-2");
    }
}
