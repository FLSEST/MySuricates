package com.automation.repository;

import com.automation.entity.Capability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CapabilityRepository extends JpaRepository<Capability, String> {

    List<Capability> findBySkillId(String skillId);

    Optional<Capability> findBySkillIdAndName(String skillId, String name);
}
