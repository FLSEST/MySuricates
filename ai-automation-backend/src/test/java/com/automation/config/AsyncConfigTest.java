package com.automation.config;

import com.automation.concurrency.DynamicSemaphore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    private AsyncConfig config;

    @BeforeEach
    void setUp() throws Exception {
        config = new AsyncConfig();
        setMaxConcurrent(10);
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(AsyncConfig.class.isAnnotationPresent(Configuration.class))
                .as("AsyncConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveEnableAsyncAnnotation_when_classIsInspected() {
        assertThat(AsyncConfig.class.isAnnotationPresent(EnableAsync.class))
                .as("AsyncConfig must be annotated with @EnableAsync")
                .isTrue();
    }

    @Test
    void should_haveBeanAnnotation_when_virtualThreadExecutorMethodIsInspected() throws NoSuchMethodException {
        Method method = AsyncConfig.class.getMethod("virtualThreadExecutor");
        assertThat(method.isAnnotationPresent(Bean.class))
                .as("virtualThreadExecutor() must be annotated with @Bean")
                .isTrue();
    }

    @Test
    void should_haveBeanAnnotation_when_executionSemaphoreMethodIsInspected() throws NoSuchMethodException {
        Method method = AsyncConfig.class.getMethod("executionSemaphore");
        assertThat(method.isAnnotationPresent(Bean.class))
                .as("executionSemaphore() must be annotated with @Bean")
                .isTrue();
    }

    // --- virtualThreadExecutor tests ---

    @Test
    void should_returnNonNullExecutor_when_virtualThreadExecutorIsCalled() {
        ExecutorService executor = config.virtualThreadExecutor();
        assertThat(executor).isNotNull();
        executor.shutdown();
    }

    @Test
    void should_returnExecutorService_when_virtualThreadExecutorIsCalled() {
        ExecutorService executor = config.virtualThreadExecutor();
        assertThat(executor).isInstanceOf(ExecutorService.class);
        executor.shutdown();
    }

    @Test
    void should_returnNewInstanceEachCall_when_virtualThreadExecutorIsCalledTwice() {
        ExecutorService executor1 = config.virtualThreadExecutor();
        ExecutorService executor2 = config.virtualThreadExecutor();
        assertThat(executor1).isNotSameAs(executor2);
        executor1.shutdown();
        executor2.shutdown();
    }

    @Test
    void should_returnFunctionalExecutor_when_virtualThreadExecutorIsCalled() throws Exception {
        ExecutorService executor = config.virtualThreadExecutor();
        var future = executor.submit(() -> "test-result");
        assertThat(future.get()).isEqualTo("test-result");
        executor.shutdown();
    }

    // --- executionSemaphore tests ---

    @Test
    void should_returnNonNullSemaphore_when_executionSemaphoreIsCalled() {
        DynamicSemaphore semaphore = config.executionSemaphore();
        assertThat(semaphore).isNotNull();
    }

    @Test
    void should_returnSemaphoreWithDefaultPermits_when_maxConcurrentIsDefault() {
        DynamicSemaphore semaphore = config.executionSemaphore();
        assertThat(semaphore.getMaxPermits()).isEqualTo(10);
        assertThat(semaphore.availablePermits()).isEqualTo(10);
    }

    @Test
    void should_returnSemaphoreWithCustomPermits_when_maxConcurrentIsOverridden() throws Exception {
        setMaxConcurrent(5);
        DynamicSemaphore semaphore = config.executionSemaphore();
        assertThat(semaphore.getMaxPermits()).isEqualTo(5);
        assertThat(semaphore.availablePermits()).isEqualTo(5);
    }

    @Test
    void should_returnSemaphoreWithOnePermit_when_maxConcurrentIsOne() throws Exception {
        setMaxConcurrent(1);
        DynamicSemaphore semaphore = config.executionSemaphore();
        assertThat(semaphore.getMaxPermits()).isEqualTo(1);
    }

    @Test
    void should_returnSemaphoreWithHighPermits_when_maxConcurrentIsLarge() throws Exception {
        setMaxConcurrent(100);
        DynamicSemaphore semaphore = config.executionSemaphore();
        assertThat(semaphore.getMaxPermits()).isEqualTo(100);
        assertThat(semaphore.availablePermits()).isEqualTo(100);
    }

    @Test
    void should_returnNewSemaphoreEachCall_when_executionSemaphoreIsCalledTwice() {
        DynamicSemaphore semaphore1 = config.executionSemaphore();
        DynamicSemaphore semaphore2 = config.executionSemaphore();
        assertThat(semaphore1).isNotSameAs(semaphore2);
    }

    // --- Helper ---

    private void setMaxConcurrent(int value) throws Exception {
        Field field = AsyncConfig.class.getDeclaredField("maxConcurrent");
        field.setAccessible(true);
        field.setInt(config, value);
    }
}
