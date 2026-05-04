package com.automation.controller;

import com.automation.config.RateLimitConfig;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import com.automation.dto.request.UpdateChainRequest;
import com.automation.dto.response.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import com.automation.service.activitylog.ActivityLogService;

@WebMvcTest(ChainController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ChainControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private ChainService chainService;
    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean private ChainExecutor chainExecutor;
    @MockitoBean private ChainMapper chainMapper;
    @MockitoBean private ChainRunRepository chainRunRepository;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    private ChainResponse sampleChain() {
        return new ChainResponse("c-1", "My Chain", "desc", null, List.of(),
                true, 0, Map.of(), Instant.now(), Instant.now());
    }

    private ChainRunResponse sampleRun() {
        return new ChainRunResponse("r-1", "c-1", "My Chain", RunStatus.COMPLETED,
                List.of(), Map.of(), Instant.now(), Instant.now(), 1000L, null, Map.of(), null);
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

    @Test
    void updateChain_returnsUpdatedChain() throws Exception {
        UpdateChainRequest req = new UpdateChainRequest("Renamed", "new desc", null, List.of(), false);
        var entity = WorkflowChain.builder().id("c-1").build();
        when(chainService.update(eq("c-1"), any(UpdateChainRequest.class))).thenReturn(entity);
        when(chainMapper.toResponse(entity)).thenReturn(sampleChain());

        mockMvc.perform(put("/api/v1/chains/c-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("c-1"));

        verify(chainService).update(eq("c-1"), any(UpdateChainRequest.class));
    }

    @Test
    void updateChain_returns404_whenChainMissing() throws Exception {
        UpdateChainRequest req = new UpdateChainRequest("X", null, null, List.of(), null);
        when(chainService.update(eq("missing"), any(UpdateChainRequest.class)))
                .thenThrow(new java.util.NoSuchElementException("Chain not found"));

        mockMvc.perform(put("/api/v1/chains/missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getChain_returns404_whenChainMissing() throws Exception {
        when(chainService.findById("missing")).thenThrow(new java.util.NoSuchElementException("not found"));

        mockMvc.perform(get("/api/v1/chains/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void executeChain_returns404_whenChainMissing() throws Exception {
        when(chainService.findById("missing")).thenThrow(new java.util.NoSuchElementException("not found"));

        mockMvc.perform(post("/api/v1/chains/missing/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRun_returns404_whenRunMissing() throws Exception {
        when(chainRunRepository.findById("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/chains/c-1/runs/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listAllRuns_returnsPagedResponse() throws Exception {
        ChainRun run = mock(ChainRun.class);
        Page<ChainRun> page = new PageImpl<>(List.of(run), Pageable.ofSize(50), 1);
        when(chainRunRepository.findFiltered(eq(null), eq(null), any(Pageable.class))).thenReturn(page);
        when(chainMapper.toRunResponseList(any())).thenReturn(List.of(sampleRun()));

        mockMvc.perform(get("/api/v1/chains/runs?page=0&size=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("r-1"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listAllRuns_filtersByStatusAndSearch() throws Exception {
        Page<ChainRun> page = new PageImpl<>(List.of(), Pageable.ofSize(20), 0);
        when(chainRunRepository.findFiltered(eq(RunStatus.COMPLETED), eq("foo"), any(Pageable.class))).thenReturn(page);
        when(chainMapper.toRunResponseList(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/chains/runs?page=0&size=20&status=completed&search=foo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(chainRunRepository).findFiltered(eq(RunStatus.COMPLETED), eq("foo"), any(Pageable.class));
    }
}
