package com.automation.controller;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.security.AllowedOrigins;
import com.automation.service.mcp.McpServer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP endpoint (Streamable HTTP, stateless): an AI client posts JSON-RPC messages and gets the answer as
 * JSON. Behind the API key like every other endpoint; only while the module {@code mcp} is on, which the
 * startup configuration alone decides.
 */
@RestController
@RequestMapping("/api/v1/mcp")
@RequiresModule(ModuleCatalog.MCP)
@RequiredArgsConstructor
public class McpController {

    private final McpServer server;
    private final AllowedOrigins allowedOrigins;
    private final ObjectMapper objectMapper;

    /**
     * Handles one MCP message (JSON-RPC 2.0) or a batch and answers with JSON; a notification gets 202.
     * A foreign browser {@code Origin} is refused with 403, an unsupported {@code MCP-Protocol-Version} with 400.
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> post(
            @RequestBody(required = false) String body,
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion) {
        // MCP requires the origin check against DNS rebinding; clients outside a browser send none.
        if (origin != null && !allowedOrigins.isAllowed(origin)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("Origin not allowed: " + origin));
        }
        if (protocolVersion != null && !McpServer.PROTOCOL_VERSIONS.contains(protocolVersion)) {
            return ResponseEntity.badRequest().body(errorBody("Unsupported MCP-Protocol-Version " + protocolVersion
                    + " — supported: " + String.join(", ", McpServer.PROTOCOL_VERSIONS)));
        }
        JsonNode message;
        try {
            message = body == null || body.isBlank() ? null : objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            message = null;
        }
        if (message == null) {
            return ResponseEntity.badRequest().body(McpServer.parseError());
        }
        if (message.isArray()) {
            return batch((ArrayNode) message);
        }
        return server.handle(message)
                .<ResponseEntity<JsonNode>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.accepted().build());
    }

    /** Answers 405: this server opens no event stream, it sends no messages of its own. */
    @GetMapping
    public ResponseEntity<Void> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    /** Answers 405: the server is stateless, there is no session to end. */
    @DeleteMapping
    public ResponseEntity<Void> endSession() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    private ResponseEntity<JsonNode> batch(ArrayNode messages) {
        if (messages.isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("Empty batch"));
        }
        ArrayNode answers = objectMapper.createArrayNode();
        messages.forEach(m -> server.handle(m).ifPresent(answers::add));
        return answers.isEmpty() ? ResponseEntity.accepted().build() : ResponseEntity.ok(answers);
    }

    private static ObjectNode errorBody(String message) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("jsonrpc", "2.0");
        body.putNull("id");
        body.putObject("error").put("code", -32600).put("message", message);
        return body;
    }
}
