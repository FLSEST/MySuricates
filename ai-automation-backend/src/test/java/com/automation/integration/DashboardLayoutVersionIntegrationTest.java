package com.automation.integration;

import com.automation.config.DashboardLayoutConfig;
import com.automation.entity.DashboardLayoutVersion;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.DashboardLayoutVersionRepository;
import com.automation.service.dashboard.DashboardLayoutVersionService;
import com.automation.service.dashboard.DashboardLayoutVersionService.SaveResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves against real SQLite: dedupe, optimistic conflict, forced save, retention with named versions. */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class DashboardLayoutVersionIntegrationTest {

    @Autowired private DashboardLayoutVersionService service;
    @Autowired private DashboardLayoutVersionRepository repository;
    @Autowired private DashboardLayoutConfig config;
    @Autowired private ObjectMapper objectMapper;

    private int originalRetention;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        originalRetention = config.getRetentionCount();
    }

    @AfterEach
    void restoreRetention() {
        config.setRetentionCount(originalRetention);
    }

    @Test
    void first_save_creates_a_version_with_canonical_content() {
        SaveResult result = service.save(body("todos", null, false));

        assertThat(result.created()).isTrue();
        DashboardLayoutVersion stored = service.get(result.version().getId());
        assertThat(stored.getDeviceLabel()).isEqualTo("Laptop");
        assertThat(stored.getFormatVersion()).isEqualTo(2);
        assertThat(stored.getViewCount()).isEqualTo(1);
        assertThat(stored.getContent()).isEqualTo(
                "{\"activeViewId\":\"default\",\"views\":[{\"id\":\"default\",\"name\":\"\",\"widgets\":"
                        + "[{\"instanceId\":\"todos\",\"type\":\"todos\"}]}]}");
    }

    @Test
    void identical_content_is_not_stored_twice_even_with_a_stale_base() {
        String first = service.save(body("todos", null, false)).version().getId();

        SaveResult again = service.save(body("todos", "someone-else", false));

        assertThat(again.created()).isFalse();
        assertThat(again.version().getId()).isEqualTo(first);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void a_stale_base_is_rejected_unless_the_save_is_forced() {
        String v1 = service.save(body("todos", null, false)).version().getId();
        service.save(body("recent-runs", v1, false));

        assertThatThrownBy(() -> service.save(body("quick-actions", v1, false)))
                .isInstanceOf(IllegalStateException.class);

        SaveResult forced = service.save(body("quick-actions", v1, true));
        assertThat(forced.created()).isTrue();
        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    void keeps_the_newest_unnamed_versions_and_every_named_one() {
        config.setRetentionCount(2);
        String v1 = service.save(body("todos", null, false)).version().getId();
        service.rename(v1, name("vor dem Umbau"));
        String v2 = service.save(body("recent-runs", v1, false)).version().getId();
        String v3 = service.save(body("quick-actions", v2, false)).version().getId();
        String v4 = service.save(body("stat-version", v3, false)).version().getId();

        assertThat(repository.findAll()).extracting(DashboardLayoutVersion::getId)
                .containsExactlyInAnyOrder(v1, v3, v4);
    }

    @Test
    void retention_below_one_still_keeps_the_newest_version() {
        config.setRetentionCount(0);
        String v1 = service.save(body("todos", null, false)).version().getId();
        String v2 = service.save(body("recent-runs", v1, false)).version().getId();

        assertThat(repository.findAll()).extracting(DashboardLayoutVersion::getId).contains(v2);
    }

    @Test
    void newest_version_follows_save_order_even_within_one_millisecond() {
        String v1 = service.save(body("todos", null, false)).version().getId();
        String v2 = service.save(body("recent-runs", v1, false)).version().getId();

        assertThat(service.list(0, 1).getContent().get(0).getId()).isEqualTo(v2);
    }

    @Test
    void rename_sets_and_clears_the_name() {
        String id = service.save(body("todos", null, false)).version().getId();

        assertThat(service.rename(id, name("vor dem Umbau")).getName()).isEqualTo("vor dem Umbau");
        assertThat(service.rename(id, name(null)).getName()).isNull();
    }

    @Test
    void unknown_version_is_not_found() {
        assertThatThrownBy(() -> service.get("missing")).isInstanceOf(ResourceNotFoundException.class);
    }

    private JsonNode body(String widgetType, String baseVersionId, boolean force) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("deviceLabel", "Laptop");
        body.put("formatVersion", 2);
        if (baseVersionId != null) {
            body.put("baseVersionId", baseVersionId);
        }
        body.put("force", force);
        ObjectNode layout = body.putObject("layout");
        layout.put("activeViewId", "default");
        ObjectNode view = layout.putArray("views").addObject();
        view.put("id", "default");
        view.put("name", "");
        view.putArray("widgets").addObject().put("instanceId", widgetType).put("type", widgetType);
        return body;
    }

    private JsonNode name(String value) {
        ObjectNode node = objectMapper.createObjectNode();
        if (value == null) {
            node.putNull("name");
        } else {
            node.put("name", value);
        }
        return node;
    }
}
