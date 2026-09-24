package com.automation.service.integration.providers.gitea;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GiteaParamsTest {

    @Test
    void splitRepo_acceptsOwnerSlashRepo() {
        assertThat(GiteaParams.splitRepo(" org/app ")).containsExactly("org", "app");
    }

    @Test
    void splitRepo_rejectsOtherShapes() {
        for (String bad : new String[]{"", "app", "org/", "/app", "a/b/c", null}) {
            assertThatThrownBy(() -> GiteaParams.splitRepo(bad))
                    .as("'%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("owner/repo");
        }
    }

    @Test
    void csv_trimsAndDropsEmpties_andAcceptsLists() {
        assertThat(GiteaParams.csv(" a, ,b ,")).containsExactly("a", "b");
        assertThat(GiteaParams.csv(List.of("x", " ", "y "))).containsExactly("x", "y");
        assertThat(GiteaParams.csv(null)).isEmpty();
    }

    @Test
    void limit_isClampedToGiteaMaximum() {
        assertThat(GiteaParams.limit(Map.of("perPage", 500), 30)).isEqualTo(50);
        assertThat(GiteaParams.limit(Map.of("perPage", "0"), 30)).isEqualTo(1);
        assertThat(GiteaParams.limit(Map.of(), 30)).isEqualTo(30);
    }

    @Test
    void paramInt_rejectsNonNumbers() {
        assertThatThrownBy(() -> GiteaParams.paramInt(Map.of("page", "zwei"), "page", 1))
                .hasMessage("Parameter 'page' must be a number");
    }

    @Test
    void requireNumber_acceptsDigitsOnly() {
        assertThat(GiteaParams.requireNumber(Map.of("number", 12), "number")).isEqualTo("12");
        assertThat(GiteaParams.requireNumber(Map.of("number", "7"), "number")).isEqualTo("7");
        assertThatThrownBy(() -> GiteaParams.requireNumber(Map.of("number", "1/../2"), "number"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GiteaParams.requireNumber(Map.of(), "number"))
                .hasMessage("Required parameter missing: number");
    }

    @Test
    void requireParam_treatsBlankAsMissing() {
        assertThatThrownBy(() -> GiteaParams.requireParam(Map.of("title", "  "), "title"))
                .hasMessage("Required parameter missing: title");
    }
}
