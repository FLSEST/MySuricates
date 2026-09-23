package com.automation.config;

import com.automation.security.AllowedOrigins;
import com.automation.service.system.OwnTailnetAddresses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorsConfigTest {

    private final OwnTailnetAddresses ownTailnet = mock(OwnTailnetAddresses.class);

    private CorsConfig config;

    @BeforeEach
    void setUp() {
        when(ownTailnet.hosts()).thenReturn(Set.of("100.64.0.7", "mine.tail1234.ts.net"));
        useConfiguredOrigins(AllowedOrigins.DEFAULT_ORIGINS);
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
    void should_containLocalhostOriginPatternsOnly_when_defaultOriginsAreUsed() throws Exception {
        CorsConfiguration corsConfig = extractCorsConfiguration();
        assertThat(corsConfig.getAllowedOriginPatterns()).containsExactly(
                "http://localhost:3000", "http://localhost:5173", "https://localhost:3000", "https://localhost:5173");
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
    void should_notAllowCredentials_when_corsConfigIsInspected() throws Exception {
        // a8056256: authentication is the X-API-Key header, no cookies — nothing to share cross-origin
        CorsConfiguration corsConfig = extractCorsConfiguration();
        assertThat(corsConfig.getAllowCredentials()).isNull();
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

    @Test
    void should_matchWebSocketPath_when_corsConfigSourceIsQueried() throws Exception {
        CorsConfigurationSource source = extractCorsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/info");
        assertThat(source.getCorsConfiguration(request)).isNotNull();
    }

    // --- The filter itself, as the backend sees a request behind the Vite proxy ---

    @Test
    void filter_rejectsForeignTailnet() throws Exception {
        MockHttpServletResponse response = filter("https://evil.tail0000.ts.net:3000");
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void filter_rejectsLookalikeHundredHost() throws Exception {
        assertThat(filter("http://100.evil.example:3000").getStatus()).isEqualTo(403);
    }

    @Test
    void filter_allowsOwnTailscaleName_withoutCredentials() throws Exception {
        MockHttpServletResponse response = filter("https://mine.tail1234.ts.net:3000");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://mine.tail1234.ts.net:3000");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void filter_allowsLocalhost_withoutCredentials() throws Exception {
        MockHttpServletResponse response = filter("http://localhost:5173");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    // --- Custom allowed origins (AUTOMATION_CORS_ORIGINS) ---

    @Test
    void should_useSingleOrigin_when_allowedOriginsHasOneValue() throws Exception {
        useConfiguredOrigins("https://example.com");
        CorsConfiguration corsConfig = extractCorsConfiguration();
        assertThat(corsConfig.getAllowedOriginPatterns())
                .containsExactly("https://example.com");
    }

    @Test
    void should_useMultipleOrigins_when_allowedOriginsHasThreeValues() throws Exception {
        useConfiguredOrigins("http://a.com,http://b.com,http://c.com");
        CorsConfiguration corsConfig = extractCorsConfiguration();
        assertThat(corsConfig.getAllowedOriginPatterns())
                .containsExactly("http://a.com", "http://b.com", "http://c.com")
                .hasSize(3);
    }

    // --- Helper ---

    private void useConfiguredOrigins(String value) {
        config = new CorsConfig(new AllowedOrigins(value, ownTailnet));
    }

    private MockHttpServletResponse filter(String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/health");
        request.setServerName("localhost");
        request.setServerPort(9090);
        request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        config.corsFilter().doFilter(request, response, new MockFilterChain());
        return response;
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
