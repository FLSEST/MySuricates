package com.automation.service.integration.providers.caldav;

import com.automation.service.integration.IntegrationAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Invariants of the catalog: unique names, complete descriptions, required fields exist as properties. */
@SuppressWarnings("unchecked")
class CalDavActionCatalogTest {

    private final List<IntegrationAction> actions = CalDavActionCatalog.actions();

    @Test
    void offersTheFiveEventActionsWithUniqueNames() {
        assertThat(actions).extracting(IntegrationAction::name)
                .containsExactly("list_calendars", "list_events", "create_event", "update_event", "delete_event")
                .doesNotHaveDuplicates();
    }

    @Test
    void everyActionHasDescriptionAndObjectSchema() {
        for (IntegrationAction a : actions) {
            assertThat(a.description()).as(a.name()).isNotBlank();
            assertThat(a.inputSchema()).as(a.name()).containsEntry("type", "object").containsKey("properties");
        }
    }

    @Test
    void requiredFieldsAreDeclaredProperties_andEveryPropertyIsDescribed() {
        for (IntegrationAction a : actions) {
            Map<String, Object> props = (Map<String, Object>) a.inputSchema().get("properties");
            List<String> required = (List<String>) a.inputSchema().getOrDefault("required", List.of());
            assertThat(props.keySet()).as(a.name()).containsAll(required);
            props.forEach((k, v) -> assertThat(((Map<String, Object>) v).get("description")).as(a.name() + "." + k).isNotNull());
        }
    }

    @Test
    void requiredFieldsMatchWhatTheEventsRequire() {
        assertThat(required("create_event")).containsExactlyInAnyOrder("calendar", "summary", "start");
        assertThat(required("update_event")).containsExactly("href");
        assertThat(required("delete_event")).containsExactly("href");
        assertThat(required("list_events")).isEmpty();
    }

    @Test
    void defaultTimeZoneMatchesTheImplementation() {
        Map<String, Object> props = (Map<String, Object>) schema("create_event").get("properties");
        assertThat((Map<String, Object>) props.get("timeZone")).containsEntry("default", CalDavEvents.DEFAULT_TIME_ZONE);
    }

    private Map<String, Object> schema(String name) {
        return actions.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow().inputSchema();
    }

    private Set<Object> required(String name) {
        return Set.copyOf((List<Object>) schema(name).getOrDefault("required", List.of()));
    }
}
