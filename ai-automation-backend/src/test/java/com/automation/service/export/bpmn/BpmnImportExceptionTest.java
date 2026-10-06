package com.automation.service.export.bpmn;

import com.automation.exception.ErrorResponse;
import com.automation.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Contract: import errors are IllegalArgumentExceptions and thus reach the client as HTTP 400. */
class BpmnImportExceptionTest {

    @Test
    void istIllegalArgumentMitUnveraenderterMeldung() {
        BpmnImportException ex = new BpmnImportException("Keine Prozess-Definition gefunden");
        assertThat(ex).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Keine Prozess-Definition gefunden")
                .hasNoCause();
    }

    @Test
    void globalerHandlerBildetImportFehlerAlsBadRequestAb() {
        BpmnImportException ex = catchThrowableOfType(BpmnImportException.class,
                () -> new BpmnImportAdapter(new ObjectMapper()).importFrom("<kein xml"));

        ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler().handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(response.getBody().getError()).startsWith(BpmnImportAdapter.MSG_INVALID_XML);
    }
}
