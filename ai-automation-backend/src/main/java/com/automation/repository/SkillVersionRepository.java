package com.automation.repository;

import com.automation.entity.SkillVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkillVersionRepository extends JpaRepository<SkillVersion, String> {

    Optional<SkillVersion> findTopBySkillIdOrderByVersionNumberDesc(String skillId);

    List<SkillVersion> findBySkillIdOrderByVersionNumberDesc(String skillId);
}
