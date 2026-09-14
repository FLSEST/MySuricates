package com.automation.modules;

import com.automation.exception.ErrorCode;
import com.automation.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/** Rejects requests to handlers of disabled modules with 404 {@code MODULE_DISABLED}. */
public class ModuleGuardInterceptor implements HandlerInterceptor {

    private final ModuleRegistry modules;
    private final ObjectMapper objectMapper;

    public ModuleGuardInterceptor(ModuleRegistry modules, ObjectMapper objectMapper) {
        this.modules = modules;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresModule requirement = requirementOf(handlerMethod);
        if (requirement == null || modules.isEnabled(requirement.value())) {
            return true;
        }
        ModuleCatalog module = requirement.value();
        ErrorResponse body = ErrorResponse.of(ErrorCode.MODULE_DISABLED,
                module.disabledMessage(request.getMethod() + " " + request.getRequestURI()));
        response.setStatus(ErrorCode.MODULE_DISABLED.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
        return false;
    }

    static RequiresModule requirementOf(HandlerMethod handlerMethod) {
        RequiresModule onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequiresModule.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequiresModule.class);
    }
}
