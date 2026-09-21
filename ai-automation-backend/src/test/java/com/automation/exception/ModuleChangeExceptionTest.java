package com.automation.exception;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ModuleChangeExceptionTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void carriesCodeAndMessage() {
        var ex = new ModuleChangeException(ErrorCode.MODULES_NOT_EDITABLE, "not editable");

        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getCode()).isEqualTo(ErrorCode.MODULES_NOT_EDITABLE);
        assertThat(ex.getMessage()).isEqualTo("not editable");
    }

    @Test
    void notEditableInstanceMapsTo403() {
        ModuleChangeException ex = catchThrowableOfType(ModuleChangeException.class,
                () -> ModuleRegistry.of(Map.of(), Map.of()).checkUiOverrides(Map.of(ModuleCatalog.CHAINS, true)));

        ResponseEntity<ErrorResponse> response = handler.handleModuleChange(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getCode()).isEqualTo("MODULES_NOT_EDITABLE");
        assertThat(response.getBody().getError()).contains("automation.modules.ui-editable");
    }

    @Test
    void hardeningSwitchMapsTo400() {
        ModuleChangeException ex = catchThrowableOfType(ModuleChangeException.class,
                () -> ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of())
                        .checkUiOverrides(Map.of(ModuleCatalog.SEALING, true)));

        ResponseEntity<ErrorResponse> response = handler.handleModuleChange(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void dependencyViolationMapsTo409() {
        ModuleChangeException ex = catchThrowableOfType(ModuleChangeException.class,
                () -> ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of())
                        .checkUiOverrides(Map.of(ModuleCatalog.WISHES, true)));

        ResponseEntity<ErrorResponse> response = handler.handleModuleChange(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo("STATE_CONFLICT");
        assertThat(response.getBody().getError()).contains("chains");
    }
}
