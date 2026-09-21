package com.automation.dto.response;

import com.automation.entity.DashboardLayoutVersion;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardLayoutVersionListItemTest {

    @Test
    void projection_is_closed_and_never_exposes_the_layout_content() {
        assertThat(DashboardLayoutVersionListItem.class.isInterface()).isTrue();
        assertThat(Arrays.stream(DashboardLayoutVersionListItem.class.getDeclaredMethods()).map(Method::getName))
                .containsExactlyInAnyOrder("getId", "getCreatedAt", "getDeviceLabel", "getName",
                        "getFormatVersion", "getViewCount", "getContentHash")
                .doesNotContain("getContent");
    }

    @Test
    void every_projection_getter_exists_on_the_entity_with_the_same_type() throws Exception {
        // Spring Data resolves a closed projection by property name; a getter the entity does not have
        // (or has with another type) would only fail at runtime.
        for (Method getter : DashboardLayoutVersionListItem.class.getDeclaredMethods()) {
            Method entityGetter = DashboardLayoutVersion.class.getMethod(getter.getName());
            assertThat(entityGetter.getReturnType()).as(getter.getName()).isEqualTo(getter.getReturnType());
        }
    }

    @Test
    void summary_mirrors_the_projection_field_by_field() {
        assertThat(Arrays.stream(DashboardLayoutVersionSummary.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("id", "createdAt", "deviceLabel", "name", "formatVersion", "viewCount", "contentHash");
    }
}
