package com.automation.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class StaticFrontendConfigTest {

    private static final String INDEX_HTML_CONTENT = "<html><body>SPA App</body></html>";
    private static final String TEST_JS_CONTENT = "console.log('test');";
    private static final String CSS_CONTENT = "body { color: red; }";

    @TempDir
    static Path frontendDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("automation.serve-frontend", () -> "true");
        registry.add("automation.frontend-path", frontendDir::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void createFrontendFiles() throws IOException {
        Files.writeString(frontendDir.resolve("index.html"), INDEX_HTML_CONTENT);
        Files.writeString(frontendDir.resolve("test.js"), TEST_JS_CONTENT);
        Path assetsDir = Files.createDirectory(frontendDir.resolve("assets"));
        Files.writeString(assetsDir.resolve("style.css"), CSS_CONTENT);
    }

    // --- Root path forwards to index.html ---

    @Test
    void rootPath_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    // --- Static file serving ---

    @Test
    void staticFile_existingJsFile_servedCorrectly() throws Exception {
        mockMvc.perform(get("/test.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(TEST_JS_CONTENT));
    }

    @Test
    void staticFile_indexHtml_servedDirectly() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(INDEX_HTML_CONTENT));
    }

    @Test
    void staticFile_nestedAsset_servedCorrectly() throws Exception {
        mockMvc.perform(get("/assets/style.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(CSS_CONTENT));
    }

    // --- SPA routing: unknown paths fall back to index.html ---

    @Test
    void spaRouting_nonExistentPath_fallsBackToIndexHtml() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(INDEX_HTML_CONTENT));
    }

    @Test
    void spaRouting_deepNonExistentPath_fallsBackToIndexHtml() throws Exception {
        mockMvc.perform(get("/some/deep/route"))
                .andExpect(status().isOk())
                .andExpect(content().string(INDEX_HTML_CONTENT));
    }

    // --- Excluded paths: api, ws, actuator are NOT intercepted ---

    @Test
    void excludedPath_apiRequest_notHandledByFrontend() throws Exception {
        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("SPA App"))));
    }

    @Test
    void excludedPath_actuatorRequest_notHandledByFrontend() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("SPA App"))));
    }

    @Test
    void excludedPath_wsRequest_notHandledByFrontend() throws Exception {
        mockMvc.perform(get("/ws/test"))
                .andExpect(content().string(not(containsString("SPA App"))));
    }
}
