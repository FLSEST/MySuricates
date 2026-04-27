package com.automation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import com.automation.concurrency.DynamicSemaphore;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${automation.execution.max-concurrent:10}")
    private int maxConcurrent;

    @Bean
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public DynamicSemaphore executionSemaphore() {
        return new DynamicSemaphore(maxConcurrent);
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
