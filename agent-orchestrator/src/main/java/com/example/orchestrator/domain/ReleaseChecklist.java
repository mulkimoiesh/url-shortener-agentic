package com.example.orchestrator.domain;

import java.util.List;

public record ReleaseChecklist(
        boolean approved,
        List<String> checklistItems,
        String notes
) {
}
