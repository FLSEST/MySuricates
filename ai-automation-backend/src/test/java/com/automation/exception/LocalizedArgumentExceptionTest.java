package com.automation.exception;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalizedArgumentExceptionTest {

    @Test
    void should_remainAnIllegalArgumentException_forExistingCallers() {
        var ex = new LocalizedArgumentException(ErrorMessage.MALFORMED_BODY);

        assertThat(ex).isInstanceOf(IllegalArgumentException.class).isInstanceOf(LocalizedError.class);
    }

    @Test
    void should_useTemplateAsMessage_withEmptyParams_when_noParamsGiven() {
        var ex = new LocalizedArgumentException(ErrorMessage.MALFORMED_BODY);

        assertThat(ex.getMessage()).isEqualTo("Request body is missing or malformed");
        assertThat(ex.errorMessage()).isEqualTo(ErrorMessage.MALFORMED_BODY);
        assertThat(ex.params()).isEmpty();
    }

    @Test
    void should_fillPlaceholders_andCopyParams_when_paramsGiven() {
        Map<String, String> params = new HashMap<>(Map.of("name", "projectId"));
        var ex = new LocalizedArgumentException(ErrorMessage.MISSING_PARAMETER, params);
        params.put("name", "changed");

        assertThat(ex.getMessage()).isEqualTo("Missing required parameter: projectId");
        assertThat(ex.params()).containsExactlyEntriesOf(Map.of("name", "projectId"));
    }

    @Test
    void should_keepPlaceholderVisible_when_paramIsMissing() {
        var ex = new LocalizedArgumentException(ErrorMessage.MISSING_PARAMETER);

        assertThat(ex.getMessage()).isEqualTo("Missing required parameter: {name}");
    }
}
