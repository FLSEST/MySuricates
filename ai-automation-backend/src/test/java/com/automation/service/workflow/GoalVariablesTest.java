package com.automation.service.workflow;

import com.automation.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * When the goal variables apply and what goes to {@link GoalContextSource} (TODO 441a4c46). The gap in
 * every variable for an unknown goal is set by the collector, see {@link WorkflowContextDataServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class GoalVariablesTest {

    @Mock private GoalContextSource goalContextSource;

    /** Module goals on; individual tests switch it off (TODO c60bf914). */
    @org.mockito.Spy private com.automation.modules.ModuleRegistry modules = com.automation.modules.ModuleRegistry.allEnabled();

    @InjectMocks private GoalVariables goalVariables;

    private static PlatformVariableSource.Request run(Object goalId, Object locale) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("goal_id", goalId);
        variables.put("goal_locale", locale);
        return new PlatformVariableSource.Request("wf-1", "proj-1", Map.of(), variables::get);
    }

    @Test
    void names_withoutOrWithABlankGoalId_areNone() {
        assertThat(goalVariables.names(run(null, null))).isEmpty();
        assertThat(goalVariables.names(run("  ", "en"))).isEmpty();
        verifyNoInteractions(goalContextSource);
    }

    @Test
    void names_withAGoalId_areEveryGoalVariable() {
        assertThat(goalVariables.names(run("g-1", null))).isEqualTo(GoalContextSource.VARIABLES);
    }

    @Test
    void load_passesTheIdReducedToIdCharacters_andTheLocale() {
        when(goalContextSource.variables("g-1rm-rf", "en")).thenReturn(Map.of("goal_status", "ok"));

        assertThat(goalVariables.load(run("g-1'; rm -rf /", "en"))).containsExactlyEntriesOf(Map.of("goal_status", "ok"));
    }

    @Test
    void load_unknownGoal_throws_soTheCollectorStatesTheGap() {
        when(goalContextSource.variables("missing", null)).thenThrow(new ResourceNotFoundException("Goal", "missing"));

        assertThatThrownBy(() -> goalVariables.load(run("missing", null))).isInstanceOf(ResourceNotFoundException.class);
    }

    /** Module goals off (TODO c60bf914): no goal variables, no query, no gap warning. */
    @Test
    void names_whileGoalsIsOff_areNone() {
        org.mockito.Mockito.doReturn(false).when(modules).isEnabled(com.automation.modules.ModuleCatalog.GOALS);

        assertThat(goalVariables.names(run("g-1", "de"))).isEmpty();
        verifyNoInteractions(goalContextSource);
    }
}
