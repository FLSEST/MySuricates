package com.automation.service.skill;

import com.automation.dto.request.CreateSkillRequest;
import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillChangeSource;
import com.automation.entity.SkillCategory;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.CapabilityRepository;
import com.automation.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private CapabilityRepository capabilityRepository;

    @Mock
    private SkillRegistry skillRegistry;

    @Mock
    private SkillMutationGateway mutationGateway;

    @InjectMocks
    private SkillService skillService;

    private Skill testSkill;

    @BeforeEach
    void setUp() {
        testSkill = Skill.builder()
                .id("skill-1")
                .name("Document Analyzer")
                .description("Analyzes documents")
                .category(SkillCategory.DOCUMENT)
                .aiContext("You are a document analyzer.")
                .builtin(true)
                .build();
    }

    @Test
    void findAll_returnsAllSkills() {
        when(skillRepository.findAll()).thenReturn(List.of(testSkill));

        List<Skill> result = skillService.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Document Analyzer");
    }

    @Test
    void findById_existingSkill_returnsSkill() {
        when(skillRepository.findById("skill-1")).thenReturn(Optional.of(testSkill));

        Skill result = skillService.findById("skill-1");

        assertThat(result.getName()).isEqualTo("Document Analyzer");
    }

    @Test
    void findById_nonExistent_throwsException() {
        when(skillRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> skillService.findById("unknown"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Skill");
    }

    @Test
    void findByCategory_returnsMatchingSkills() {
        when(skillRepository.findByCategory(SkillCategory.DOCUMENT)).thenReturn(List.of(testSkill));

        List<Skill> result = skillService.findByCategory(SkillCategory.DOCUMENT);

        assertThat(result).hasSize(1);
    }

    @Test
    void findCapabilities_existingSkill_returnsCapabilities() {
        Capability cap = Capability.builder()
                .id("cap-1")
                .name("ocr")
                .skill(testSkill)
                .build();
        when(skillRepository.findById("skill-1")).thenReturn(Optional.of(testSkill));
        when(capabilityRepository.findBySkillId("skill-1")).thenReturn(List.of(cap));

        List<Capability> result = skillService.findCapabilities("skill-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("ocr");
    }

    @Test
    void findCapabilities_nonExistentSkill_throwsException() {
        when(skillRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> skillService.findCapabilities("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_validRequest_createsAndRegistersSkill() {
        var request = new CreateSkillRequest(
                "Custom Skill", "A custom skill", "icon", SkillCategory.CODE,
                "You are a custom skill.", Map.of("key", "schema")
        );
        when(mutationGateway.save(any(Skill.class), eq(SkillChangeSource.USER))).thenAnswer(inv -> {
            Skill s = inv.getArgument(0);
            s.setId("skill-new");
            return s;
        });

        Skill result = skillService.create(request);

        assertThat(result.getName()).isEqualTo("Custom Skill");
        assertThat(result.isBuiltin()).isFalse();
        verify(skillRegistry).register(result);
    }

    @Test
    void delete_customSkill_deletesAndUnregisters() {
        Skill customSkill = Skill.builder()
                .id("skill-custom")
                .name("Custom")
                .category(SkillCategory.CODE)
                .builtin(false)
                .build();
        when(skillRepository.findById("skill-custom")).thenReturn(Optional.of(customSkill));

        skillService.delete("skill-custom");

        verify(mutationGateway).delete(customSkill, SkillChangeSource.USER);
        verify(skillRegistry).unregister("skill-custom");
    }

    @Test
    void delete_builtinSkill_throwsException() {
        when(skillRepository.findById("skill-1")).thenReturn(Optional.of(testSkill));

        assertThatThrownBy(() -> skillService.delete("skill-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("built-in");

        verify(mutationGateway, never()).delete(any(), any());
    }
}
