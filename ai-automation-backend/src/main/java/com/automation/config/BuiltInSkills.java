package com.automation.config;

import com.automation.entity.Skill;

import java.util.ArrayList;
import java.util.List;

/**
 * Static definitions of all built-in skills.
 * These are seeded into the database on first startup.
 *
 * <p>The definitions themselves live in one class per category — {@link BuiltInCodeSkills},
 * {@link BuiltInDocumentSkills}, {@link BuiltInDataSkills}, {@link BuiltInContentSkills},
 * {@link BuiltInResearchSkills}, {@link BuiltInBusinessSkills}, {@link BuiltInFactorySkills} —
 * concatenated here in the curated order. Builder helpers live in {@link BuiltInSkillSupport}.
 */
public final class BuiltInSkills {

    private BuiltInSkills() {}

    public static List<Skill> getAll() {
        List<Skill> skills = new ArrayList<>();

        skills.addAll(BuiltInCodeSkills.all());
        skills.addAll(BuiltInDocumentSkills.all());
        skills.addAll(BuiltInDataSkills.all());
        skills.addAll(BuiltInContentSkills.all());
        skills.addAll(BuiltInResearchSkills.all());
        skills.addAll(BuiltInBusinessSkills.all());
        skills.addAll(BuiltInFactorySkills.all());

        return skills;
    }
}
