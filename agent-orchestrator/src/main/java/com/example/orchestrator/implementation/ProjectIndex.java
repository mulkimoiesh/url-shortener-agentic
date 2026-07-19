package com.example.orchestrator.implementation;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public record ProjectIndex(List<ClassInfo> classes, String persistenceApi) {

    public Optional<ClassInfo> findByPath(String relativePath) {
        return classes.stream().filter(c -> c.relativePath().equals(relativePath)).findFirst();
    }

    public List<ClassInfo> byKind(ClassKind kind) {
        return classes.stream().filter(c -> c.kind() == kind).toList();
    }

    public List<String> allEndpointMappings() {
        return classes.stream().flatMap(c -> c.endpointMappings().stream()).toList();
    }

    /** Compact, prompt-friendly summary - deliberately not the full file contents. */
    public String summarize() {
        if (classes.isEmpty()) {
            return "(empty project - no existing classes)";
        }
        return classes.stream()
                .collect(Collectors.groupingBy(ClassInfo::kind))
                .entrySet().stream()
                .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
                .map(e -> e.getKey() + ":\n" + e.getValue().stream()
                        .map(c -> "  - " + c.simpleName() + " (" + c.relativePath() + ")"
                                + (c.endpointMappings().isEmpty() ? "" : " endpoints=" + c.endpointMappings()))
                        .collect(Collectors.joining("\n")))
                .collect(Collectors.joining("\n"));
    }
}
