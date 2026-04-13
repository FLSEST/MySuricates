package com.automation.dto.response;

import com.automation.dto.response.ExportSkillResponse.ExportCapabilityResponse;
import com.automation.entity.SkillCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportSkillResponseTest {

    // --- ExportSkillResponse: Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var capability = new ExportCapabilityResponse(
                "cap-1", "Cap description", "com.Handler",
                Map.of("input", "string"), Map.of("output", "string"), true
        );
        var response = new ExportSkillResponse(
                "skill-name", "skill-desc", "icon-1", SkillCategory.CODE,
                "ai-context", Map.of("key", "value"), "1.0.0", List.of(capability)
        );

        assertThat(response.name()).isEqualTo("skill-name");
        assertThat(response.description()).isEqualTo("skill-desc");
        assertThat(response.icon()).isEqualTo("icon-1");
        assertThat(response.category()).isEqualTo(SkillCategory.CODE);
        assertThat(response.aiContext()).isEqualTo("ai-context");
        assertThat(response.configSchema()).containsEntry("key", "value");
        assertThat(response.version()).isEqualTo("1.0.0");
        assertThat(response.capabilities()).containsExactly(capability);
    }

    @Test
    void should_acceptEmptyCapabilities_when_constructed() {
        var response = new ExportSkillResponse(
                "skill", "desc", "icon", SkillCategory.DOCUMENT,
                "ctx", Map.of(), "2.0.0", List.of()
        );

        assertThat(response.capabilities()).isEmpty();
        assertThat(response.configSchema()).isEmpty();
    }

    @Test
    void should_acceptNullFields_when_constructed() {
        var response = new ExportSkillResponse(
                null, null, null, null, null, null, null, null
        );

        assertThat(response.name()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.icon()).isNull();
        assertThat(response.category()).isNull();
        assertThat(response.aiContext()).isNull();
        assertThat(response.configSchema()).isNull();
        assertThat(response.version()).isNull();
        assertThat(response.capabilities()).isNull();
    }

    @Test
    void should_acceptPartialNulls_when_constructed() {
        var response = new ExportSkillResponse(
                "skill", null, null, SkillCategory.DATA,
                null, null, "1.0.0", null
        );

        assertThat(response.name()).isEqualTo("skill");
        assertThat(response.description()).isNull();
        assertThat(response.category()).isEqualTo(SkillCategory.DATA);
        assertThat(response.version()).isEqualTo("1.0.0");
        assertThat(response.capabilities()).isNull();
    }

    // --- ExportSkillResponse: Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var cap = new ExportCapabilityResponse(
                "cap", "desc", "handler", Map.of(), Map.of(), false
        );
        var a = new ExportSkillResponse(
                "skill", "desc", "icon", SkillCategory.CONTENT,
                "ctx", Map.of("k", "v"), "1.0.0", List.of(cap)
        );
        var b = new ExportSkillResponse(
                "skill", "desc", "icon", SkillCategory.CONTENT,
                "ctx", Map.of("k", "v"), "1.0.0", List.of(cap)
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentName() {
        var a = new ExportSkillResponse("a", "desc", "icon", SkillCategory.CODE, "ctx", Map.of(), "1.0", List.of());
        var b = new ExportSkillResponse("b", "desc", "icon", SkillCategory.CODE, "ctx", Map.of(), "1.0", List.of());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentCategory() {
        var a = new ExportSkillResponse("s", "d", "i", SkillCategory.CODE, "c", Map.of(), "1.0", List.of());
        var b = new ExportSkillResponse("s", "d", "i", SkillCategory.DATA, "c", Map.of(), "1.0", List.of());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentVersion() {
        var a = new ExportSkillResponse("s", "d", "i", SkillCategory.CODE, "c", Map.of(), "1.0", List.of());
        var b = new ExportSkillResponse("s", "d", "i", SkillCategory.CODE, "c", Map.of(), "2.0", List.of());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothAllNull() {
        var a = new ExportSkillResponse(null, null, null, null, null, null, null, null);
        var b = new ExportSkillResponse(null, null, null, null, null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var response = new ExportSkillResponse("s", "d", "i", SkillCategory.CODE, "c", Map.of(), "1.0", List.of());

        assertThat(response).isNotEqualTo(null);
    }

    // --- ExportSkillResponse: toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new ExportSkillResponse(
                "my-skill", "my-desc", "my-icon", SkillCategory.RESEARCH,
                "ai-ctx", Map.of("schema", "val"), "3.0.0", List.of()
        );
        var str = response.toString();

        assertThat(str).contains("my-skill");
        assertThat(str).contains("my-desc");
        assertThat(str).contains("my-icon");
        assertThat(str).contains("RESEARCH");
        assertThat(str).contains("3.0.0");
    }

    // --- ExportCapabilityResponse: Constructor & accessors ---

    @Test
    void should_setAllCapabilityFields_when_constructed() {
        var cap = new ExportCapabilityResponse(
                "cap-name", "cap-desc", "com.Handler",
                Map.of("in", "type"), Map.of("out", "type"), true
        );

        assertThat(cap.name()).isEqualTo("cap-name");
        assertThat(cap.description()).isEqualTo("cap-desc");
        assertThat(cap.handlerClass()).isEqualTo("com.Handler");
        assertThat(cap.inputSchema()).containsEntry("in", "type");
        assertThat(cap.outputSchema()).containsEntry("out", "type");
        assertThat(cap.async()).isTrue();
    }

    @Test
    void should_returnFalseAsync_when_constructedWithFalse() {
        var cap = new ExportCapabilityResponse(
                "cap", "desc", "handler", Map.of(), Map.of(), false
        );

        assertThat(cap.async()).isFalse();
    }

    @Test
    void should_acceptNullCapabilityFields_when_constructed() {
        var cap = new ExportCapabilityResponse(null, null, null, null, null, false);

        assertThat(cap.name()).isNull();
        assertThat(cap.description()).isNull();
        assertThat(cap.handlerClass()).isNull();
        assertThat(cap.inputSchema()).isNull();
        assertThat(cap.outputSchema()).isNull();
        assertThat(cap.async()).isFalse();
    }

    @Test
    void should_acceptEmptySchemas_when_capabilityConstructed() {
        var cap = new ExportCapabilityResponse(
                "cap", "desc", "handler", Map.of(), Map.of(), true
        );

        assertThat(cap.inputSchema()).isEmpty();
        assertThat(cap.outputSchema()).isEmpty();
    }

    // --- ExportCapabilityResponse: Record equality ---

    @Test
    void should_capabilityBeEqual_when_sameFieldValues() {
        var a = new ExportCapabilityResponse("cap", "desc", "handler", Map.of("k", "v"), Map.of(), true);
        var b = new ExportCapabilityResponse("cap", "desc", "handler", Map.of("k", "v"), Map.of(), true);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_capabilityNotBeEqual_when_differentName() {
        var a = new ExportCapabilityResponse("a", "desc", "handler", Map.of(), Map.of(), false);
        var b = new ExportCapabilityResponse("b", "desc", "handler", Map.of(), Map.of(), false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_capabilityNotBeEqual_when_differentAsync() {
        var a = new ExportCapabilityResponse("cap", "desc", "handler", Map.of(), Map.of(), true);
        var b = new ExportCapabilityResponse("cap", "desc", "handler", Map.of(), Map.of(), false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_capabilityNotBeEqual_when_differentHandlerClass() {
        var a = new ExportCapabilityResponse("cap", "desc", "handler1", Map.of(), Map.of(), false);
        var b = new ExportCapabilityResponse("cap", "desc", "handler2", Map.of(), Map.of(), false);

        assertThat(a).isNotEqualTo(b);
    }

    // --- ExportCapabilityResponse: toString ---

    @Test
    void should_containCapabilityFieldValues_when_toStringCalled() {
        var cap = new ExportCapabilityResponse(
                "my-cap", "my-cap-desc", "com.MyHandler",
                Map.of("in", "val"), Map.of("out", "val"), true
        );
        var str = cap.toString();

        assertThat(str).contains("my-cap");
        assertThat(str).contains("my-cap-desc");
        assertThat(str).contains("com.MyHandler");
    }
}
