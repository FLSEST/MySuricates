package com.automation.controller;

import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.UpdateChainRequest;
import com.automation.dto.response.ChainResponse;
import com.automation.dto.response.ChainRunResponse;
import com.automation.dto.response.PageResponse;
import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowChain;
import com.automation.mapper.ChainMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.chain.ChainExecutor;
import com.automation.service.chain.ChainService;
import com.automation.util.EnumParameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Workflow chains: define sequences of workflows, start them and follow their runs. */
@RequiresModule(ModuleCatalog.CHAINS)
@RestController
@RequestMapping("/api/v1/chains")
@RequiredArgsConstructor
public class ChainController {

    private final ChainService chainService;
    private final ChainExecutor chainExecutor;
    private final ChainMapper chainMapper;

    /** Lists all workflow chains. */
    @GetMapping
    public List<ChainResponse> listChains() {
        return chainMapper.toResponseList(chainService.findAll());
    }

    /** Creates a chain from an ordered list of workflow entries and returns it with 201. */
    @PostMapping
    public ResponseEntity<ChainResponse> createChain(@Valid @RequestBody CreateChainRequest request) {
        WorkflowChain chain = chainService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(chainMapper.toResponse(chain));
    }

    /** Returns one chain with its entries. */
    @GetMapping("/{id}")
    public ChainResponse getChain(@PathVariable String id) {
        return chainMapper.toResponse(chainService.findById(id));
    }

    /** Updates a chain; fields left out stay unchanged, {@code entries} replaces the whole entry list. */
    @PutMapping("/{id}")
    public ChainResponse updateChain(@PathVariable String id, @Valid @RequestBody UpdateChainRequest request) {
        return chainMapper.toResponse(chainService.update(id, request));
    }

    /**
     * Deletes a chain; answers 204.
     * Refused with 409 while the chain has an active run ({@code PENDING}, {@code RUNNING} or {@code PAUSED}),
     * which would otherwise go on starting the chain's workflows; cancel the run first.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteChain(@PathVariable String id) {
        chainService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Starts a chain run in the background and returns it with 201; the entries then run one after another.
     * The optional body is the input context of the run.
     */
    @PostMapping("/{id}/execute")
    public ResponseEntity<ChainRunResponse> executeChain(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> inputContext) {
        WorkflowChain chain = chainService.findById(id);
        ChainRun run = chainExecutor.execute(chain, inputContext);
        return ResponseEntity.status(HttpStatus.CREATED).body(chainMapper.toRunResponse(run));
    }

    /**
     * Lists the runs of all chains page by page, newest first.
     * {@code status} ignores case (unknown value: 400); {@code search} matches the chain name.
     */
    @GetMapping("/runs")
    public PageResponse<ChainRunResponse> listAllRuns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {

        Pageable pageable = PageRequest.of(page, size);

        RunStatus runStatus = EnumParameter.parseOptional(RunStatus.class, "status", status);
        String q = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<ChainRun> result = chainService.findRunsFiltered(runStatus, q, pageable);

        return new PageResponse<>(
                chainMapper.toRunResponseList(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    /** Lists the runs of one chain, newest first. */
    @GetMapping("/{id}/runs")
    public List<ChainRunResponse> listRuns(@PathVariable String id) {
        return chainMapper.toRunResponseList(chainService.findRunsByChainId(id));
    }

    /** Returns one chain run with its entry runs. */
    @GetMapping("/{id}/runs/{runId}")
    public ChainRunResponse getRun(@PathVariable String id, @PathVariable String runId) {
        return chainMapper.toRunResponse(chainService.findRunById(runId));
    }

    /**
     * Asks a running chain run to stop; it starts no further entries.
     * Answers 200 even if the run is not active.
     */
    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<Void> cancelRun(@PathVariable String runId) {
        chainExecutor.cancel(runId);
        return ResponseEntity.ok().build();
    }
}
