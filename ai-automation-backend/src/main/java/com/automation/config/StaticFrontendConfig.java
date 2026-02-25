package com.automation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

@Configuration
@ConditionalOnProperty(name = "automation.serve-frontend", havingValue = "true")
public class StaticFrontendConfig implements WebMvcConfigurer {

    @Value("${automation.frontend-path:/app/frontend}")
    private String frontendPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + frontendPath + "/";

        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        // Serve the requested file if it exists, otherwise fall back to index.html for SPA routing
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // Don't fall back for API or WebSocket paths
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("ws/") || resourcePath.startsWith("actuator/")) {
                            return null;
                        }
                        return new FileSystemResource(frontendPath + "/index.html");
                    }
                });
    }
}
