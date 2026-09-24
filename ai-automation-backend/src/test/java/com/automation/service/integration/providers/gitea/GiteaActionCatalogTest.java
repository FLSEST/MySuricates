package com.automation.service.integration.providers.gitea;

import com.automation.service.integration.IntegrationAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class GiteaActionCatalogTest {

    @Test
    void offersTheSixIssueActions() {
        assertThat(GiteaActionCatalog.actions()).extracting(IntegrationAction::name)
                .containsExactly("list_repos", "list_issues", "get_issue", "create_issue", "update_issue", "add_comment");
    }

    @Test
    void repoScopedActionsRequireRepo() {
        for (IntegrationAction a : GiteaActionCatalog.actions()) {
            if (a.name().equals("list_repos")) continue;
            assertThat((List<Object>) a.inputSchema().get("required")).as(a.name()).contains("repo");
        }
    }

    @Test
    void everyRequiredFieldIsDescribed() {
        for (IntegrationAction a : GiteaActionCatalog.actions()) {
            Map<Object, Object> properties = (Map<Object, Object>) a.inputSchema().get("properties");
            Object required = a.inputSchema().get("required");
            if (required == null) continue;
            assertThat(properties.keySet()).as(a.name()).containsAll((List<Object>) required);
        }
    }

    @Test
    void declaresNoScopes_becauseGiteaCannotReportThem() {
        assertThat(GiteaActionCatalog.actions()).allSatisfy(a -> assertThat(a.requiredScopes()).isEmpty());
    }
}
