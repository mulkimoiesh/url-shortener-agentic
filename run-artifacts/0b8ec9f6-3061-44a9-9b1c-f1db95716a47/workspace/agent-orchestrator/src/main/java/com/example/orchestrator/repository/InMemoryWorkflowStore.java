package com.example.orchestrator.repository;

import com.example.orchestrator.state.WorkflowState;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryWorkflowStore {

    private final ConcurrentHashMap<String, WorkflowState> runs = new ConcurrentHashMap<>();

    public void save(WorkflowState state) {
        runs.put(state.getRunId(), state);
    }

    public Optional<WorkflowState> findById(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    public java.util.List<WorkflowState> findAll() {
        return java.util.List.copyOf(runs.values());
    }
}
