package com.example.orchestrator.domain;

import java.util.List;

/**
 * The contract every downstream agent builds on. functionalRequirements and
 * nonFunctionalRequirements replace the old single "stories" list so
 * Architecture has a precise, checkable list of capabilities to design for
 * (this is what Implementation's functional-completeness check verifies
 * against later). clarificationQuestions is populated instead of
 * ambiguities being silently resolved when scenarioType is AMBIGUOUS.
 */
public record RequirementSpec(
        List<String> functionalRequirements,
        List<String> nonFunctionalRequirements,
        List<String> acceptanceCriteria,
        List<String> ambiguities,
        List<String> clarificationQuestions,
        List<String> assumptions
) {
}
