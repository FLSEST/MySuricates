package com.automation.controller;

import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.response.ChainResponse;
import com.automation.dto.response.ChainRunResponse;
import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowChain;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.mapper.ChainMapper;
import com.automation.repository.ChainRunRepository;
import com.automation.service.chain.ChainExecutor;
import com.automation.service.chain.ChainService;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChainController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ChainControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private ChainService chainService;
    @MockBean private ChainExecutor chainExecutor;
    @MockBean private ChainMapper chainMapper;
    @MockBean private ChainRunRepository chainRunRepository;
    @MockBean private ApiKeyService apiKeyService;

    private ChainResponse sampleChain() {
        return new ChainResponse("c-1", "My Chain", "desc", null, List.of(),
                true, 0, Map.of(), Instant.now(), Instant.now());
    }

    private ChainRunResponse sampleRun() {
        return new ChainRunResponse("r-1", "c-1", "My Chain", RunStatus.COMPLETED,
                List.of(), Map.of(), Instant.now(), Instant.now(), 1000L, null, Map.of());
    }

    @Test
    void listChains_returnsAll() throws Exception {
        when(chainService.findAll()).thenReturn(List.of());
        when(chainMapper.toResponseList(any())).thenReturn(List.of(sampleChain()));

        mockMvc.perform(get("/api/v1/chains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("c-1"));
    }

    @Test
    void getChain_returnsById() throws Exception {
        var chain = WorkflowChain.builder().id("c-1").name("My Chain").build();
        when(chainService.findById("c-1")).thenReturn(chain);
        when(chainMapper.toResponse(chain)).thenReturn(sampleChain());

        mockMvc.perform(get("/api/v1/chains/c-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My Chain"));
    }

    @Test
    void createChain_returns201() throws Exception {
        var chain = WorkflowChain.builder().id("c-1").name("New Chain").build();
        when(chainService.create(any())).thenReturn(chain);
        when(chainMapper.toResponse(chain)).thenReturn(sampleChain());

        var request = Map.of("name", "New Chain", "entries", List.of(
                Map.of("workflowId", "w-1", "sortOrder", 0)));

        mockMvc.perform(post("/api/v1/chains")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void deleteChain_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/chains/c-1"))
                .andExpect(status().isNoContent());
        verify(chainService).delete("c-1");
    }

    @Test
    void executeChain_returns201() throws Exception {
        var chain = WorkflowChain.builder().id("c-1").name("My Chain").build();
        var run = ChainRun.builder().id("r-1").chain(WorkflowChain.builder().id("c-1").build()).build();
        when(chainService.findById("c-1")).thenReturn(chain);
        when(chainExecutor.execute(eq(chain), any())).thenReturn(run);
        when(chainMapper.toRunResponse(run)).thenReturn(sampleRun());

        mockMvc.perform(post("/api/v1/chains/c-1/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("r-1"));
    }

    @Test
    void listRuns_returnsForChain() throws Exception {
        var chain = WorkflowChain.builder().id("c-1").build();
        when(chainService.findById("c-1")).thenReturn(chain);
        when(chainRunRepository.findByChainIdOrderByStartedAtDesc("c-1")).thenReturn(List.of());
        when(chainMapper.toRunResponseList(any())).thenReturn(List.of(sampleRun()));

        mockMvc.perform(get("/api/v1/chains/c-1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chainId").value("c-1"));
    }

    @Test
    void getRun_returnsById() throws Exception {
        var run = ChainRun.builder().id("r-1").chain(WorkflowChain.builder().id("c-1").build()).build();
        when(chainRunRepository.findById("r-1")).thenReturn(Optional.of(run));
        when(chainMapper.toRunResponse(run)).thenReturn(sampleRun());

        mockMvc.perform(get("/api/v1/chains/c-1/runs/r-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("r-1"));
    }

    @Test
    void cancelRun_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/chains/runs/r-1/cancel"))
                .andExpect(status().isOk());
        verify(chainExecutor).cancel("r-1");
    }
}
