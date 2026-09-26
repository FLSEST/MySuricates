package com.automation.service.system;

/**
 * Whether an AI CLI of this instance has a login — for {@link ContainerAccessReport}. Answered by the AI
 * provider status ({@code service.ai.status.AiProviderStatusService}) with the installer's login rule, so
 * the startup configuration card and the AI provider card never disagree about one machine (7296ad45).
 *
 * <p>The interface lives here, next to its only caller: {@code service.ai.status} depends on
 * {@code service.ai}, which reaches back into this package — importing it from here would pull it into
 * that package cycle ({@code PackageCycleRatchetTest}).
 */
public interface ProviderLoginCheck {

    /**
     * @param cli program name as the provider selection names it: claude, codex, pi
     * @return whether a login is there; null when no provider with this program is registered
     */
    Boolean loggedIn(String cli);
}
