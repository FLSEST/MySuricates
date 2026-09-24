package com.automation.service.integration.providers.gitlab;

import com.automation.service.integration.IntegrationAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class GitLabActionCatalogTest {

    @Test
    void offersTheSixIssueActions() {
        assertThat(GitLabActionCatalog.actions()).extracting(IntegrationAction::name)
                .containsExactly("list_projects", "list_issues", "get_issue", "create_issue", "update_issue", "add_comment");
    }

    @Test
    void onlyWritingActionsNeedTheApiScope() {
        for (IntegrationAction a : GitLabActionCatalog.actions()) {
            boolean writes = List.of("create_issue", "update_issue", "add_comment").contains(a.name());
            assertThat(a.requiredScopes()).as(a.name()).isEqualTo(writes ? List.of("api") : List.of());
        }
    }

    @Test
    void projectScopedActionsRequireProject_andRequiredFieldsAreDescribed() {
        for (IntegrationAction a : GitLabActionCatalog.actions()) {
            Map<Object, Object> properties = (Map<Object, Object>) a.inputSchema().get("properties");
            List<Object> required = (List<Object>) a.inputSchema().get("required");
            if (a.name().equals("list_projects")) {
                assertThat(required).isNull();
                continue;
            }
            assertThat(required).as(a.name()).contains("project");
            assertThat(properties.keySet()).as(a.name()).containsAll(required);
        }
    }
}
