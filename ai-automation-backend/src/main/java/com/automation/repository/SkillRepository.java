package com.automation.repository;

import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkillRepository extends JpaRepository<Skill, String> {

    List<Skill> findByCategory(SkillCategory category);

    List<Skill> findByBuiltin(boolean builtin);

    List<Skill> findByNameContainingIgnoreCase(String name);

    @Query("SELECT DISTINCT s FROM Skill s LEFT JOIN FETCH s.capabilities ORDER BY s.category, s.name")
    List<Skill> findAllWithCapabilities();

    @Query("SELECT s FROM Skill s LEFT JOIN FETCH s.capabilities WHERE s.id = :id")
    Optional<Skill> findByIdWithCapabilities(@Param("id") String id);
}
