package com.automation.service.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatedLayoutSaveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode body(boolean force) throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"deviceLabel":"Desk","formatVersion":2,"force":%s,
                 "layout":{"activeViewId":"b","views":[
                   {"id":"a","name":"A","widgets":[]},
                   {"id":"b","name":"B","widgets":[]}]}}
                """.formatted(force));
    }

    @Test
    void record_carries_its_components_and_compares_by_value() {
        ValidatedLayoutSave save = new ValidatedLayoutSave("Desk", 2, "v1", true, "{}", "abc", 3);

        assertThat(save.deviceLabel()).isEqualTo("Desk");
        assertThat(save.formatVersion()).isEqualTo(2);
        assertThat(save.baseVersionId()).isEqualTo("v1");
        assertThat(save.force()).isTrue();
        assertThat(save.canonicalContent()).isEqualTo("{}");
        assertThat(save.contentHash()).isEqualTo("abc");
        assertThat(save.viewCount()).isEqualTo(3);
        assertThat(save).isEqualTo(new ValidatedLayoutSave("Desk", 2, "v1", true, "{}", "abc", 3))
                .hasSameHashCodeAs(new ValidatedLayoutSave("Desk", 2, "v1", true, "{}", "abc", 3))
                .isNotEqualTo(new ValidatedLayoutSave("Desk", 2, "v1", false, "{}", "abc", 3));
    }

    @Test
    void validator_produces_it_with_view_count_force_and_a_missing_base() throws Exception {
        ValidatedLayoutSave save = new DashboardLayoutValidator().validateSave(body(true));

        assertThat(save.viewCount()).isEqualTo(2);
        assertThat(save.force()).isTrue();
        assertThat(save.baseVersionId()).isNull();
        assertThat(save.contentHash()).matches("[0-9a-f]{64}");
        assertThat(save.canonicalContent()).startsWith("{\"activeViewId\":\"b\"");
    }

    @Test
    void force_does_not_change_the_content_hash() throws Exception {
        DashboardLayoutValidator validator = new DashboardLayoutValidator();

        assertThat(validator.validateSave(body(true)).contentHash())
                .isEqualTo(validator.validateSave(body(false)).contentHash());
    }
}
