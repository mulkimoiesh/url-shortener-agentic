package com.example.orchestrator.domain;

import java.util.List;

/** Raw plan the Implementation Agent gets back from the LLM, before files are written. */
public record ImplementationPlan(List<FileChange> files, String notes) {

    public record FileChange(String path, String content, String action) {
    }
}
