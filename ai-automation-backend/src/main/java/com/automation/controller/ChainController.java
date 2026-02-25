package com.automation.controller;

import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.UpdateChainRequest;
import com.automation.dto.response.ChainResponse;
import com.automation.dto.response.ChainRunResponse;
import com.automation.entity.ChainRun;
import com.automation.entity.WorkflowChain;
import com.automation.mapper.ChainMapper;
import com.automation.repository.ChainRunRepository;
import com.automation.service.chain.ChainExecutor;
import com.automation.service.chain.ChainService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/chains")
@RequiredArgsConstructor
public class ChainController {

    private final ChainService chainService;
    private final ChainExecutor chainExecutor;
    private final ChainMapper chainMapper;
    private final ChainRunRepository chainRunRepository;

    @GetMapping
    public List<ChainResponse> listChains() {
        return chainMapper.toResponseList(chainService.findAll());
    }

    @PostMapping
    public ResponseEntity<ChainResponse> createChain(@Valid @RequestBody CreateChainRequest request) {
        WorkflowChain chain = chainService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(chainMapper.toResponse(chain));
    }

    @GetMapping("/{id}")
    public ChainResponse getChain(@PathVariable String id) {
        return chainMapper.toResponse(chainService.findById(id));
    }

    @PutMapping("/{id}")
    public ChainResponse updateChain(@PathVariable String id, @Valid @RequestBody UpdateChainRequest request) {
        return chainMapper.toResponse(chainService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteChain(@PathVariable String id) {
        chainService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<ChainRunResponse> executeChain(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> inputContext) {
        WorkflowChain chain = chainService.findById(id);
        ChainRun run = chainExecutor.execute(chain, inputContext);
        return ResponseEntity.status(HttpStatus.CREATED).body(chainMapper.toRunResponse(run));
    }

    @GetMapping("/{id}/runs")
    public List<ChainRunResponse> listRuns(@PathVariable String id) {
        chainService.findById(id); // verify chain exists
        return chainMapper.toRunResponseList(chainRunRepository.findByChainIdOrderByStartedAtDesc(id));
    }

    @GetMapping("/{id}/runs/{runId}")
    public ChainRunResponse getRun(@PathVariable String id, @PathVariable String runId) {
        ChainRun run = chainRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Chain run not found: " + runId));
        return chainMapper.toRunResponse(run);
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<Void> cancelRun(@PathVariable String runId) {
        chainExecutor.cancel(runId);
        return ResponseEntity.ok().build();
    }
}
