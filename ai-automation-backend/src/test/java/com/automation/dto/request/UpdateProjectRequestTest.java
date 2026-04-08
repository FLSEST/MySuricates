package com.automation.dto.request;

import com.automation.entity.ProjectType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateProjectRequestTest {

    @Test
    void should_returnName_when_constructedWithName() {
        var request = new UpdateProjectRequest("MyProject", null, null, null, null, null, null);

        assertThat(request.name()).isEqualTo("MyProject");
    }

    @Test
    void should_returnSubtitle_when_constructedWithSubtitle() {
        var request = new UpdateProjectRequest(null, "A subtitle", null, null, null, null, null);

        assertThat(request.subtitle()).isEqualTo("A subtitle");
    }

    @Test
    void should_returnDescription_when_constructedWithDescription() {
        var request = new UpdateProjectRequest(null, null, "Project description", null, null, null, null);

        assertThat(request.description()).isEqualTo("Project description");
    }

    @Test
    void should_returnType_when_constructedWithProjectType() {
        var request = new UpdateProjectRequest(null, null, null, ProjectType.CODE, null, null, null);

        assertThat(request.type()).isEqualTo(ProjectType.CODE);
    }

    @Test
    void should_returnPath_when_constructedWithPath() {
        var request = new UpdateProjectRequest(null, null, null, null, "/home/user/project", null, null);

        assertThat(request.path()).isEqualTo("/home/user/project");
    }

    @Test
    void should_returnConfig_when_constructedWithMap() {
        var config = Map.<String, Object>of("key", "value", "count", 42);
        var request = new UpdateProjectRequest(null, null, null, null, null, config, null);

        assertThat(request.config()).hasSize(2);
        assertThat(request.config()).containsEntry("key", "value");
        assertThat(request.config()).containsEntry("count", 42);
    }

    @Test
    void should_returnAiContext_when_constructedWithAiContext() {
        var request = new UpdateProjectRequest(null, null, null, null, null, null, "Use TypeScript");

        assertThat(request.aiContext()).isEqualTo("Use TypeScript");
    }

    @Test
    void should_returnNullForAllFields_when_constructedWithAllNulls() {
        var request = new UpdateProjectRequest(null, null, null, null, null, null, null);

        assertThat(request.name()).isNull();
        assertThat(request.subtitle()).isNull();
        assertThat(request.description()).isNull();
        assertThat(request.type()).isNull();
        assertThat(request.path()).isNull();
        assertThat(request.config()).isNull();
        assertThat(request.aiContext()).isNull();
    }

    @Test
    void should_returnAllFields_when_fullyPopulated() {
        var config = Map.<String, Object>of("env", "prod", "debug", false);
        var request = new UpdateProjectRequest(
                "MySuricates", "AI Automation", "Workflow platform",
                ProjectType.CODE, "/home/flo/project", config, "Spring Boot backend"
        );

        assertThat(request.name()).isEqualTo("MySuricates");
        assertThat(request.subtitle()).isEqualTo("AI Automation");
        assertThat(request.description()).isEqualTo("Workflow platform");
        assertThat(request.type()).isEqualTo(ProjectType.CODE);
        assertThat(request.path()).isEqualTo("/home/flo/project");
        assertThat(request.config()).containsExactlyInAnyOrderEntriesOf(config);
        assertThat(request.aiContext()).isEqualTo("Spring Boot backend");
    }

    @Test
    void should_handleAllProjectTypes() {
        for (ProjectType type : ProjectType.values()) {
            var request = new UpdateProjectRequest(null, null, null, type, null, null, null);
            assertThat(request.type()).isEqualTo(type);
        }
    }

    @Test
    void should_handleComplexConfig_when_nestedMapsProvided() {
        var nested = Map.<String, Object>of(
                "database", Map.of("host", "localhost", "port", 5432),
                "features", List.of("alpha", "beta"),
                "enabled", true
        );
        var request = new UpdateProjectRequest(null, null, null, null, null, nested, null);

        assertThat(request.config()).hasSize(3);
        assertThat(request.config().get("database")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var db = (Map<String, Object>) request.config().get("database");
        assertThat(db).containsEntry("host", "localhost").containsEntry("port", 5432);
        assertThat(request.config().get("features")).isInstanceOf(List.class);
        assertThat(request.config().get("enabled")).isEqualTo(true);
    }

    @Test
    void should_returnEmptyMap_when_configIsEmpty() {
        var request = new UpdateProjectRequest(null, null, null, null, null, Map.of(), null);

        assertThat(request.config()).isEmpty();
    }

    // --- equals / hashCode ---

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var config = Map.<String, Object>of("key", "value");
        var a = new UpdateProjectRequest("Name", "Sub", "Desc", ProjectType.CODE, "/path", config, "ctx");
        var b = new UpdateProjectRequest("Name", "Sub", "Desc", ProjectType.CODE, "/path", config, "ctx");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveNullFields() {
        var a = new UpdateProjectRequest(null, null, null, null, null, null, null);
        var b = new UpdateProjectRequest(null, null, null, null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveEmptyConfig() {
        var a = new UpdateProjectRequest(null, null, null, null, null, Map.of(), null);
        var b = new UpdateProjectRequest(null, null, null, null, null, Collections.emptyMap(), null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_nameDiffers() {
        var a = new UpdateProjectRequest("Alpha", null, null, null, null, null, null);
        var b = new UpdateProjectRequest("Beta", null, null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_subtitleDiffers() {
        var a = new UpdateProjectRequest("Name", "Sub A", null, null, null, null, null);
        var b = new UpdateProjectRequest("Name", "Sub B", null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_descriptionDiffers() {
        var a = new UpdateProjectRequest(null, null, "Desc A", null, null, null, null);
        var b = new UpdateProjectRequest(null, null, "Desc B", null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_typeDiffers() {
        var a = new UpdateProjectRequest(null, null, null, ProjectType.CODE, null, null, null);
        var b = new UpdateProjectRequest(null, null, null, ProjectType.RESEARCH, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_pathDiffers() {
        var a = new UpdateProjectRequest(null, null, null, null, "/path/a", null, null);
        var b = new UpdateProjectRequest(null, null, null, null, "/path/b", null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_configDiffers() {
        var a = new UpdateProjectRequest(null, null, null, null, null, Map.of("a", 1), null);
        var b = new UpdateProjectRequest(null, null, null, null, null, Map.of("b", 2), null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_aiContextDiffers() {
        var a = new UpdateProjectRequest(null, null, null, null, null, null, "Context A");
        var b = new UpdateProjectRequest(null, null, null, null, null, null, "Context B");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new UpdateProjectRequest("Name", "Sub", "Desc", ProjectType.CODE, "/path", Map.of(), "ctx");

        assertThat(request).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldNamesInToString() {
        var request = new UpdateProjectRequest("Name", "Sub", "Desc", ProjectType.CODE, "/path", Map.of(), "ctx");

        assertThat(request.toString()).contains("name");
        assertThat(request.toString()).contains("subtitle");
        assertThat(request.toString()).contains("description");
        assertThat(request.toString()).contains("type");
        assertThat(request.toString()).contains("path");
        assertThat(request.toString()).contains("config");
        assertThat(request.toString()).contains("aiContext");
    }

    @Test
    void should_containValuesInToString() {
        var request = new UpdateProjectRequest("MySuricates", "AI Platform", "Desc", ProjectType.BUSINESS, "/home", Map.of(), "ctx");

        assertThat(request.toString()).contains("MySuricates");
        assertThat(request.toString()).contains("AI Platform");
        assertThat(request.toString()).contains("BUSINESS");
    }
}
