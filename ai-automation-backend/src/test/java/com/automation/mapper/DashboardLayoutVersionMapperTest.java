package com.automation.mapper;

import com.automation.dto.response.DashboardLayoutVersionListItem;
import com.automation.dto.response.DashboardLayoutVersionSummary;
import com.automation.entity.DashboardLayoutVersion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardLayoutVersionMapperTest {

    private static final Instant CREATED = Instant.parse("2026-09-15T10:00:00Z");

    private final DashboardLayoutVersionMapper mapper = new DashboardLayoutVersionMapperImpl();

    private static DashboardLayoutVersionListItem item(String id, String name) {
        return new DashboardLayoutVersionListItem() {
            public String getId() { return id; }
            public Instant getCreatedAt() { return CREATED; }
            public String getDeviceLabel() { return "Laptop"; }
            public String getName() { return name; }
            public int getFormatVersion() { return 2; }
            public int getViewCount() { return 4; }
            public String getContentHash() { return "hash-" + id; }
        };
    }

    @Test
    void maps_an_entity_to_a_summary_without_content() {
        DashboardLayoutVersion version = DashboardLayoutVersion.builder()
                .id("v1").createdAt(CREATED).deviceLabel("Phone").name("Release").formatVersion(2).viewCount(1)
                .contentHash("abc").content("{\"big\":\"layout\"}").build();

        assertThat(mapper.toSummary(version))
                .isEqualTo(new DashboardLayoutVersionSummary("v1", CREATED, "Phone", "Release", 2, 1, "abc"));
    }

    @Test
    void maps_a_projection_to_a_summary() {
        assertThat(mapper.toSummary(item("v2", null)))
                .isEqualTo(new DashboardLayoutVersionSummary("v2", CREATED, "Laptop", null, 2, 4, "hash-v2"));
    }

    @Test
    void maps_a_projection_list_in_order() {
        assertThat(mapper.toSummaries(List.of(item("a", "A"), item("b", null))))
                .extracting(DashboardLayoutVersionSummary::id, DashboardLayoutVersionSummary::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a", "A"), org.assertj.core.groups.Tuple.tuple("b", null));
    }

    @Test
    void null_inputs_map_to_null() {
        assertThat(mapper.toSummary((DashboardLayoutVersion) null)).isNull();
        assertThat(mapper.toSummary((DashboardLayoutVersionListItem) null)).isNull();
        assertThat(mapper.toSummaries(null)).isNull();
        assertThat(mapper.toSummaries(List.of())).isEmpty();
    }
}
