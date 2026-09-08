package com.automation.service.factory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FactoryTopicCatalogTest {

    @Test
    void getTopicCategories_returns8Categories() {
        List<Map<String, String>> categories = FactoryTopicCatalog.getTopicCategories();

        assertThat(categories).hasSize(8);
        assertThat(categories.stream().map(c -> c.get("id")).toList())
                .containsExactly("CODE", "CONTENT", "DATA", "RESEARCH", "TRANSFORM", "DEVOPS", "CREATIVE", "CUSTOM");
        assertThat(categories).allSatisfy(c -> assertThat(c).containsKeys("id", "name", "description"));
    }
}
