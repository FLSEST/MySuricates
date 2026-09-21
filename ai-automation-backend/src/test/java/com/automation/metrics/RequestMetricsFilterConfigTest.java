package com.automation.metrics;

import com.automation.config.RequestMetricsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

class RequestMetricsFilterConfigTest {

    @Test
    void registersTheCostFilterOutermostForTheApiOnly() {
        FilterRegistrationBean<RequestCostFilter> registration = new RequestMetricsFilterConfig()
                .requestCostFilterRegistration(new RequestMetricsConfig(), new RequestMetricsCollector());

        assertThat(registration.getFilter()).isInstanceOf(RequestCostFilter.class);
        assertThat(registration.getUrlPatterns()).containsExactly("/api/v1/*");
        assertThat(registration.getFilterName()).isEqualTo("requestCostFilter");
        // Outermost among the application filters, so auth and rate limiting count towards the request.
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
        assertThat(registration.isEnabled()).isTrue();
    }

    /** Deliberately not a component: a scanned Filter would be pulled into every @WebMvcTest slice. */
    @Test
    void filterIsNotAComponent() {
        assertThat(RequestCostFilter.class.isAnnotationPresent(org.springframework.stereotype.Component.class))
                .isFalse();
    }
}
