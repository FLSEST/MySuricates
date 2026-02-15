package com.automation.repository;

import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SkillRepository extends JpaRepository<Skill, String> {

    List<Skill> findByCategory(SkillCategory category);

    List<Skill> findByBuiltin(boolean builtin);

    List<Skill> findByNameContainingIgnoreCase(String name);
}
