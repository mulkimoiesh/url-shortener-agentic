package com.example.orchestrator.controller;

import com.example.orchestrator.controller.dto.*;
import com.example.orchestrator.codebase.WorkspaceService;
import com.example.orchestrator.graph.WorkflowEngine;
import com.example.orchestrator.metrics.MetricsCollector;
import com.example.orchestrator.metrics.RunMetrics;
import com.example.orchestrator.repository.InMemoryWorkflowStore;
import com.example.orchestrator.state.Actor;
import com.example.orchestrator.state.RunStatus;
import com.example.orchestrator.state.WorkflowState;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workflow")
public class WorkflowController {

    private final WorkflowEngine engine;
    private final InMemoryWorkflowStore store;
    private final MetricsCollector metricsCollector;
    private final WorkspaceService workspace;

    public WorkflowController(WorkflowEngine engine, InMemoryWorkflowStore store,
                               MetricsCollector metricsCollector, WorkspaceService workspace) {
        this.engine = engine;
        this.store = store;
        this.metricsCollector = metricsCollector;
        this.workspace = workspace;
    }

    @PostMapping("/runs")
    public ResponseEntity<RunView> startRun(@Valid @RequestBody StartRunRequest request) {
        WorkflowState state = engine.startRun(request.rawRequirement(), request.scenarioType());
        store.save(state);
        return ResponseEntity.status(HttpStatus.CREATED).body(RunView.from(state));
    }

    @PostMapping("/runs/{runId}/approve")
    public ResponseEntity<RunView> approveGate(@PathVariable String runId,
                                                @Valid @RequestBody ApproveGateRequest request) {
        WorkflowState state = requireRun(runId);
        WorkflowState updated;
        try {
            updated = engine.approveGate(state, request.approvedBy(), request.notes());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        store.save(updated);
        return ResponseEntity.ok(RunView.from(updated));
    }

    @PostMapping("/runs/{runId}/resolve-block")
    public ResponseEntity<RunView> resolveBlock(@PathVariable String runId,
                                                 @Valid @RequestBody ResolveBlockRequest request) {
        WorkflowState state = requireRun(runId);
        WorkflowState updated;
        try {
            updated = engine.resolveBlock(state, request.resolvedBy(), request.notes());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        store.save(updated);
        return ResponseEntity.ok(RunView.from(updated));
    }

    @GetMapping("/runs/{runId}")
    public RunView getRun(@PathVariable String runId) {
        return RunView.from(requireRun(runId));
    }

    @GetMapping("/runs")
    public List<RunView> listRuns() {
        return store.findAll().stream().map(RunView::from).toList();
    }

    @GetMapping("/runs/{runId}/audit")
    public List<?> getAuditLog(@PathVariable String runId) {
        return requireRun(runId).getDecisionLog();
    }

    @GetMapping("/runs/{runId}/metrics")
    public RunMetrics getMetrics(@PathVariable String runId) {
        return metricsCollector.collect(requireRun(runId));
    }

    /**
     * The explicit "patch, don't auto-write" step from the design discussion:
     * only callable once a run has reached COMPLETED (i.e. every gate,
     * including RELEASE, was approved by a human). Copies the validated,
     * tested files from this run's isolated workspace into the live
     * shortener-service module. Nothing before this point ever touches the
     * real product.
     */
    @PostMapping("/runs/{runId}/apply")
    public ResponseEntity<Map<String, Object>> applyToLiveProduct(@PathVariable String runId) {
        WorkflowState state = requireRun(runId);

        if (state.getStatus() != RunStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Run " + runId + " is not COMPLETED (status=" + state.getStatus()
                            + "). All gates, including RELEASE, must be approved before applying to the live product.");
        }
        if (state.getImplementation() == null || state.getImplementation().filesChanged().isEmpty()) {
            return ResponseEntity.ok(Map.of("applied", List.of(), "message", "No files to apply for this run."));
        }

        List<String> applied = workspace.applyToLiveProduct(runId, state.getImplementation().filesChanged());
        state.addDecision(state.getCurrentStage(), Actor.HUMAN,
                "Applied " + applied.size() + " file(s) to the live shortener-service module: " + applied);
        store.save(state);

        return ResponseEntity.ok(Map.of("applied", applied, "runId", runId));
    }

    private WorkflowState requireRun(String runId) {
        return store.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No run found for id " + runId));
    }
}
