package com.automation.service.integration;

import com.automation.service.integration.TaskExportCapable.TaskExportInput;
import com.automation.service.integration.TaskExportCapable.TaskRef;
import com.automation.service.integration.TaskExportCapable.TaskTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the {@link TaskExportCapable} capability contract.
 *
 * <p>{@code TaskExportCapable} is an interface with one abstract method
 * ({@link TaskExportCapable#createTask}), one {@code default} method
 * ({@link TaskExportCapable#listTargets}) and three nested records, so the tests
 * exercise the contract via small in-test implementations rather than mocks.
 */
class TaskExportCapableTest {

    /** In-test provider implementing only the abstract method (inherits the default listTargets()). */
    private static TaskExportCapable providerReturning(TaskRef ref) {
        return (input, accessToken) -> ref;
    }

    // ---- createTask: valid export ----

    @Test
    void should_returnTaskRef_when_createTaskCalledWithValidInput() {
        TaskRef expected = new TaskRef("github-issue", "42", "https://github.com/o/r/issues/42");
        TaskExportCapable provider = providerReturning(expected);

        TaskRef result = provider.createTask(
                new TaskExportInput("Title", "Body", List.of("bug"), "owner/repo"),
                "token-123");

        assertThat(result).isSameAs(expected);
        assertThat(result.referenceType()).isEqualTo("github-issue");
        assertThat(result.referenceId()).isEqualTo("42");
        assertThat(result.url()).isEqualTo("https://github.com/o/r/issues/42");
    }

    @Test
    void should_passInputAndTokenToImplementation_when_createTaskCalled() {
        AtomicReference<TaskExportInput> seenInput = new AtomicReference<>();
        AtomicReference<String> seenToken = new AtomicReference<>();
        TaskExportCapable provider = (input, accessToken) -> {
            seenInput.set(input);
            seenToken.set(accessToken);
            return new TaskRef("t", "1", "u");
        };
        TaskExportInput input = new TaskExportInput("T", "B", List.of("a", "b"), "target");

        provider.createTask(input, "my-token");

        assertThat(seenInput.get()).isSameAs(input);
        assertThat(seenToken.get()).isEqualTo("my-token");
    }

    // ---- createTask: null input ----

    @Test
    void should_propagateNullPointer_when_createTaskCalledWithNullInputAndImplementationReadsIt() {
        // The contract does not mandate null-validation; an implementation that reads
        // the input fails on null. Verify the interface does not swallow that.
        TaskExportCapable provider = (input, accessToken) -> new TaskRef(input.title(), "1", "u");

        assertThatThrownBy(() -> provider.createTask(null, "token"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_allowNullInput_when_implementationToleratesIt() {
        TaskExportCapable provider = (input, accessToken) -> new TaskRef("none", "0", "");

        TaskRef result = provider.createTask(null, null);

        assertThat(result.referenceType()).isEqualTo("none");
    }

    // ---- createTask: exception propagation (external-system failure) ----

    @Test
    void should_propagateException_when_implementationThrows() {
        RuntimeException failure = new IllegalStateException("export failed");
        TaskExportCapable provider = (input, accessToken) -> { throw failure; };

        assertThatThrownBy(() -> provider.createTask(
                new TaskExportInput("T", "B", List.of(), "x"), "token"))
                .isSameAs(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("export failed");
    }

    // ---- listTargets: default method ----

    @Test
    void should_returnEmptyList_when_listTargetsNotOverridden() {
        TaskExportCapable provider = providerReturning(new TaskRef("t", "1", "u"));

        assertThat(provider.listTargets("token")).isEmpty();
    }

    @Test
    void should_returnImmutableList_when_listTargetsNotOverridden() {
        TaskExportCapable provider = providerReturning(new TaskRef("t", "1", "u"));
        List<TaskTarget> targets = provider.listTargets("token");

        assertThatThrownBy(() -> targets.add(new TaskTarget("id", "label")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_returnConfiguredTargets_when_listTargetsOverridden() {
        TaskExportCapable provider = new TaskExportCapable() {
            @Override
            public TaskRef createTask(TaskExportInput input, String accessToken) {
                return new TaskRef("t", "1", "u");
            }
            @Override
            public List<TaskTarget> listTargets(String accessToken) {
                return List.of(new TaskTarget("repo1", "Repo One"), new TaskTarget("repo2", "Repo Two"));
            }
        };

        List<TaskTarget> targets = provider.listTargets("token");

        assertThat(targets).hasSize(2)
                .extracting(TaskTarget::id)
                .containsExactly("repo1", "repo2");
        assertThat(targets).extracting(TaskTarget::label)
                .containsExactly("Repo One", "Repo Two");
    }

    // ---- nested records ----

    @Test
    void should_exposeAllFields_when_taskExportInputCreated() {
        TaskExportInput input = new TaskExportInput("My Title", "My Body", List.of("l1", "l2"), "my-target");

        assertThat(input.title()).isEqualTo("My Title");
        assertThat(input.body()).isEqualTo("My Body");
        assertThat(input.labels()).containsExactly("l1", "l2");
        assertThat(input.target()).isEqualTo("my-target");
    }

    @Test
    void should_beValueEqual_when_taskExportInputsHaveSameFields() {
        TaskExportInput a = new TaskExportInput("t", "b", List.of("x"), "tgt");
        TaskExportInput b = new TaskExportInput("t", "b", List.of("x"), "tgt");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void should_exposeAllFields_when_taskRefCreated() {
        TaskRef ref = new TaskRef("issue", "99", "https://example.com/99");

        assertThat(ref.referenceType()).isEqualTo("issue");
        assertThat(ref.referenceId()).isEqualTo("99");
        assertThat(ref.url()).isEqualTo("https://example.com/99");
    }

    @Test
    void should_exposeAllFields_when_taskTargetCreated() {
        TaskTarget target = new TaskTarget("the-id", "The Label");

        assertThat(target.id()).isEqualTo("the-id");
        assertThat(target.label()).isEqualTo("The Label");
    }

    @Test
    void should_beValueEqual_when_taskTargetsHaveSameFields() {
        assertThat(new TaskTarget("id", "label"))
                .isEqualTo(new TaskTarget("id", "label"))
                .hasSameHashCodeAs(new TaskTarget("id", "label"));
    }
}
