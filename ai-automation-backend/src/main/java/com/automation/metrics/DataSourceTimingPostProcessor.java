package com.automation.metrics;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Wraps the application DataSource in a {@link TimingDataSource} so that the time a request
 * spends waiting for the single pooled connection can be told apart from the time it spends
 * working (TODO 5d1c3b2a).
 *
 * <p>A BeanPostProcessor rather than a {@code @Bean} override: the pool stays exactly as Spring
 * Boot configured it, including every Hikari property, and only the handing-out is intercepted.
 * Flyway is unaffected — it gets its own non-pooled DataSource (see {@code spring.flyway.url}).
 */
@Component
public class DataSourceTimingPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource && !(bean instanceof TimingDataSource)) {
            return new TimingDataSource(dataSource);
        }
        return bean;
    }
}
