package com.automation.repository;

import com.automation.entity.BlockDefinition;
import com.automation.entity.WorkflowTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class WorkflowTemplateRepositoryTest {

    @Autowired
    private WorkflowTemplateRepository repository;

    @Test
    void should_return_only_built_in_templates_when_findByBuiltInTrue() {
        WorkflowTemplate builtIn = persistTemplate("built-in-tpl", true, 10);
        WorkflowTemplate custom = persistTemplate("custom-tpl", false, 20);

        List<WorkflowTemplate> result = repository.findByBuiltInTrue();

        assertThat(result).extracting(WorkflowTemplate::getId).contains(builtIn.getId());
        assertThat(result).extracting(WorkflowTemplate::getId).doesNotContain(custom.getId());
        assertThat(result).allMatch(WorkflowTemplate::isBuiltIn);
    }

    @Test
    void should_return_only_custom_templates_when_findByBuiltInFalse() {
        WorkflowTemplate builtIn = persistTemplate("built-in-tpl", true, 10);
        WorkflowTemplate custom = persistTemplate("custom-tpl", false, 20);

        List<WorkflowTemplate> result = repository.findByBuiltInFalse();

        assertThat(result).extracting(WorkflowTemplate::getId).contains(custom.getId());
        assertThat(result).extracting(WorkflowTemplate::getId).doesNotContain(builtIn.getId());
        assertThat(result).noneMatch(WorkflowTemplate::isBuiltIn);
    }

    @Test
    void should_return_templates_ordered_by_sortOrder_ascending_when_findAllByOrderBySortOrderAsc() {
        WorkflowTemplate t30 = persistTemplate("tpl-30", false, 30);
        WorkflowTemplate t10 = persistTemplate("tpl-10", false, 10);
        WorkflowTemplate t20 = persistTemplate("tpl-20", false, 20);

        List<WorkflowTemplate> result = repository.findAllByOrderBySortOrderAsc();

        List<String> ourIds = result.stream()
                .map(WorkflowTemplate::getId)
                .filter(id -> id.equals(t10.getId()) || id.equals(t20.getId()) || id.equals(t30.getId()))
                .toList();

        assertThat(ourIds).containsExactly(t10.getId(), t20.getId(), t30.getId());
    }

    private WorkflowTemplate persistTemplate(String prefix, boolean builtIn, int sortOrder) {
        String suffix = "-" + System.nanoTime();
        BlockDefinition block = new BlockDefinition();
        block.setType("simple");
        WorkflowTemplate tpl = new WorkflowTemplate();
        tpl.setName(Map.of("en", prefix + suffix));
        tpl.setDescription(Map.of("en", "desc"));
        tpl.setIcon("icon");
        tpl.setCategory("test");
        tpl.setBlocks(new ArrayList<>(List.of(block)));
        tpl.setBuiltIn(builtIn);
        tpl.setSortOrder(sortOrder);
        return repository.saveAndFlush(tpl);
    }
}
