package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    private CorsConfig config;

    @BeforeEach
    void setUp() throws Exception {
        config = new CorsConfig();
        setAllowedOrigins("http://localhost:3000,http://localhost:5173");
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(CorsConfig.class.isAnnotationPresent(Configuration.class))
                .as("CorsConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveBeanAnnotation_when_corsFilterMethodIsInspected() throws NoSuchMethodException {
        Method method = CorsConfig.class.getMethod("corsFilter");
        assertThat(method.isAnnotationPresent(Bean.class))
                .as("corsFilter() must be annotated with @Bean")
                .isTrue();
    }

    // --- corsFilter() basic tests ---

    @Test
    void should_returnNonNullFilter_when_corsFilterIsCalled() {
        CorsFilter filter = config.corsFilter();
        assertThat(filter).isNotNull();
    }

    @Test
    void should_returnCorsFilterInstance_when_corsFilterIsCalled() {
        CorsFilter filter = config.corsFilter();
        assertThat(filter).isInstanceOf(CorsFilter.class);
    }

    @Test
    void should_returnNewInstanceEachCall_when_corsFilterIsCalledTwice() {
        CorsFilter filter1 = config.corsFilter();
        CorsFilter filter2 = config.corsFilter();
        assertThat(filter1).isNotSameAs(filter2);
    }

    // --- CORS configuration property tests ---

    @Test
    void should_containDefaultOriginPatterns_when_defaultOriginsAreUsed() throws Exception {
        CorsConfiguration corsConfig = extractCorsConfiguration();
        assertThat(corsConfig.getAllowedOriginPatterns())
                .containsExactly("http://localhost:3000", "http://localhost:5173");
    }

    @Test
    void should_containAllExpectedHttpMethods_when_corsConfigIsInspected() throws Exception {
        CorsConfiguration corsConfig = extractCorsConfiguration();
        assertThat(corsConfig.getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }

    @Test
    void should_containSixAllowedMethods_when_corsConfigIsInspected() throws Exception {
        CorsConfiguration corsConfig = extractCorsConfiguration();
        assertThat(corsConfig.getAllowedMethods()).hasSize(6);
    }

    @Test
    void should_allowAllHeaders_when_corsConfigIsInspected() throws Exception {
        CorsConfiguration corsConfig = extractCorsConfiguration();
        assertThat(corsConfig.getAllowedHeaders()).containsExactly("*");
    }

    @Test
    void should_allowCredentials_when_corsConfigIsInspected() throws Exception {
        CorsConfiguration corsConfig = extractCorsConfiguration();
        assertThat(corsConfig.getAllowCredentials()).isTrue();
    }

    // --- Path registration tests ---

    @Test
    void should_matchApiPath_when_corsConfigSourceIsQueried() throws Exception {
        CorsConfigurationSource source = extractCorsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/workflows");
        assertThat(source.getCorsConfiguration(request)).isNotNull();
    }

    @Test
    void should_matchRootPath_when_corsConfigSourceIsQueried() throws Exception {
        CorsConfigurationSource source = extractCorsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        assertThat(source.getCorsConfiguration(request)).isNotNull();
    }

    // --- Custom allowed origins ---

    @Test
    void should_useSingleOrigin_when_allowedOriginsHasOneValue() throws Exception {
        setAllowedOrigins("https://example.com");
        CorsConfiguration corsConfig = extractCorsConfiguration();
        assertThat(corsConfig.getAllowedOriginPatterns())
                .containsExactly("https://example.com");
    }

    @Test
    void should_useMultipleOrigins_when_allowedOriginsHasThreeValues() throws Exception {
        setAllowedOrigins("http://a.com,http://b.com,http://c.com");
        CorsConfiguration corsConfig = extractCorsConfiguration();
        assertThat(corsConfig.getAllowedOriginPatterns())
                .containsExactly("http://a.com", "http://b.com", "http://c.com")
                .hasSize(3);
    }

    // --- Helper ---

    private void setAllowedOrigins(String value) throws Exception {
        Field field = CorsConfig.class.getDeclaredField("allowedOrigins");
        field.setAccessible(true);
        field.set(config, value);
    }

    private CorsConfigurationSource extractCorsConfigurationSource() throws Exception {
        CorsFilter filter = config.corsFilter();
        Field sourceField = CorsFilter.class.getDeclaredField("configSource");
        sourceField.setAccessible(true);
        return (CorsConfigurationSource) sourceField.get(filter);
    }

    private CorsConfiguration extractCorsConfiguration() throws Exception {
        CorsConfigurationSource source = extractCorsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        return source.getCorsConfiguration(request);
    }
}
