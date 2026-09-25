package com.automation.service.workflow;

import com.automation.service.metrics.RequestMetricsFactSheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@code request_metrics} ist nie leer (TODO 441a4c46, 843be7a5). Dass die Variable wirklich im Kontext
 * landet, pruefen die request_metrics-Faelle in {@link WorkflowContextDataServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class RequestMetricsVariableTest {

    @Mock private RequestMetricsFactSheet requestMetricsFactSheet;

    @InjectMocks private RequestMetricsVariable requestMetrics;

    @Test
    void value_isTheFactSheet() {
        when(requestMetricsFactSheet.render()).thenReturn("FAKT request_metrics_routes_measured: 12");

        assertThat(requestMetrics.value()).isEqualTo("FAKT request_metrics_routes_measured: 12");
    }

    @Test
    void value_ofAnEmptyFactSheet_statesTheGap() {
        when(requestMetricsFactSheet.render()).thenReturn(" ");

        assertThat(requestMetrics.value()).isEqualTo("NICHT GEMESSEN: request_metrics (Faktenblatt leer)");
    }

    @Test
    void value_ofAThrowingFactSheet_throws_soTheCollectorStatesTheGap() {
        when(requestMetricsFactSheet.render()).thenThrow(new IllegalStateException("kaputt"));

        assertThatThrownBy(requestMetrics::value).isInstanceOf(IllegalStateException.class);
    }
}
