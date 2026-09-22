package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.entity.Workflow;
import com.automation.repository.SkillRepository;
import com.automation.service.skill.SkillContentHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSkillPinsTest {

    @Mock private SkillRepository skillRepository;

    private final SkillContentHasher contentHasher = new SkillContentHasher();

    private static Skill skill(String id) {
        return Skill.builder().id(id).name(id).category(SkillCategory.CODE).aiContext("ctx " + id)
                .capabilities(new ArrayList<>()).build();
    }

    @Test
    void pinsActivatedAndBlockSkillsAndMarksMissingOnes() {
        Skill activated = skill("s-activated");
        Skill blockOnly = skill("s-block");
        Workflow workflow = Workflow.builder().id("wf").activatedSkills(new ArrayList<>(List.of(activated)))
                .blocks(new ArrayList<>(List.of(
                        BlockDefinition.builder().type("simple").skillIds(new ArrayList<>(List.of("s-block", "s-gone"))).build())))
                .build();
        when(skillRepository.findAllByIdInWithCapabilities(Set.of("s-activated", "s-block", "s-gone")))
                .thenReturn(List.of(activated, blockOnly));

        Map<String, String> pins = new WorkflowSkillPins(skillRepository, contentHasher).pins(workflow);

        assertThat(pins).containsExactly(
                Map.entry("s-activated", contentHasher.hash(activated)),
                Map.entry("s-block", contentHasher.hash(blockOnly)),
                Map.entry("s-gone", WorkflowSkillPins.MISSING));
    }

    @Test
    void workflowWithoutSkillsHasNoPinsAndNeedsNoQuery() {
        Workflow workflow = Workflow.builder().id("wf").activatedSkills(new ArrayList<>()).blocks(new ArrayList<>()).build();

        assertThat(new WorkflowSkillPins(skillRepository, contentHasher).pins(workflow)).isEmpty();
        verifyNoInteractions(skillRepository);
    }

    @Test
    void changedReportsAddedRemovedAndAlteredSkills() {
        Map<String, String> sealed = Map.of("same", "1", "altered", "2", "removed", "3");
        Map<String, String> current = Map.of("same", "1", "altered", "x", "added", "4");

        assertThat(WorkflowSkillPins.changed(sealed, current)).containsExactly("added", "altered", "removed");
    }

    @Test
    void jsonRoundTripKeepsEmptyDistinctFromAbsent() {
        assertThat(WorkflowSkillPins.toJson(Map.of())).isEqualTo("{}");
        assertThat(WorkflowSkillPins.fromJson(WorkflowSkillPins.toJson(Map.of("b", "2", "a", "1"))))
                .containsExactly(Map.entry("a", "1"), Map.entry("b", "2"));
    }
}
