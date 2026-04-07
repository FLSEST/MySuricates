package com.automation.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingProfileTypeTest {

    @Test
    void should_haveAllProfileTypeValuesInDeclaredOrder() {
        assertThat(OnboardingProfileType.values())
                .containsExactly(
                        OnboardingProfileType.BASE,
                        OnboardingProfileType.PROFILE
                );
    }

    @Test
    void should_resolveBaseViaValueOf() {
        assertThat(OnboardingProfileType.valueOf("BASE")).isEqualTo(OnboardingProfileType.BASE);
    }

    @Test
    void should_resolveProfileViaValueOf() {
        assertThat(OnboardingProfileType.valueOf("PROFILE")).isEqualTo(OnboardingProfileType.PROFILE);
    }
}
