package com.automation.metrics;

import com.automation.config.RequestMetricsConfig;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RequestCostFilter} outermost, so that time spent in authentication or rate
 * limiting counts towards the request as well — that time is just as slow for the user.
 *
 * <p>Registration happens here rather than by annotating the filter {@code @Component}, because a
 * component-scanned {@code Filter} is instantiated by every {@code @WebMvcTest} slice: roughly
 * four hundred controller tests would have to mock this filter's dependencies to keep loading
 * their context. A {@code @Configuration} is not part of the MVC slice, so the filter stays out
 * of them entirely while running normally in the real application.
 */
@Configuration
public class RequestMetricsFilterConfig {

    @Bean
    public FilterRegistrationBean<RequestCostFilter> requestCostFilterRegistration(
            RequestMetricsConfig config, RequestMetricsCollector collector) {
        FilterRegistrationBean<RequestCostFilter> registration =
                new FilterRegistrationBean<>(new RequestCostFilter(config, collector));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("requestCostFilter");
        return registration;
    }
}
