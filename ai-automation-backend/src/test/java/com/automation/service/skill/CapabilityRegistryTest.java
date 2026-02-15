package com.automation.service.skill;

import com.automation.entity.Capability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRegistryTest {

    private CapabilityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CapabilityRegistry();
    }

    @Test
    void register_and_find() {
        Capability cap = Capability.builder().name("ocr").build();

        registry.register("skill-1", cap);

        Optional<Capability> found = registry.find("skill-1", "ocr");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("ocr");
    }

    @Test
    void find_nonExistent_returnsEmpty() {
        assertThat(registry.find("skill-1", "ocr")).isEmpty();
    }

    @Test
    void unregister_removesCapability() {
        Capability cap = Capability.builder().name("ocr").build();
        registry.register("skill-1", cap);

        registry.unregister("skill-1", "ocr");

        assertThat(registry.find("skill-1", "ocr")).isEmpty();
    }

    @Test
    void findBySkill_returnsAllCapabilitiesForSkill() {
        registry.register("skill-1", Capability.builder().name("ocr").build());
        registry.register("skill-1", Capability.builder().name("grammar").build());
        registry.register("skill-2", Capability.builder().name("lint").build());

        List<Capability> result = registry.findBySkill("skill-1");

        assertThat(result).hasSize(2);
    }

    @Test
    void findByName_returnsCapabilitiesAcrossSkills() {
        registry.register("skill-1", Capability.builder().name("analyze").build());
        registry.register("skill-2", Capability.builder().name("analyze").build());
        registry.register("skill-3", Capability.builder().name("other").build());

        List<Capability> result = registry.findByName("analyze");

        assertThat(result).hasSize(2);
    }

    @Test
    void findBySkill_noMatches_returnsEmptyList() {
        assertThat(registry.findBySkill("non-existent")).isEmpty();
    }
}
