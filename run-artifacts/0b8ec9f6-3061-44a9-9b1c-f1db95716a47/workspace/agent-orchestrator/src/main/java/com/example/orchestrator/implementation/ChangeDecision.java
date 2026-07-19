package com.example.orchestrator.implementation;

/**
 * The deterministic outcome of ChangePlanner for one file Architecture
 * flagged. architecturePath is what Architecture proposed; resolvedPath is
 * what Implementation should actually act on (may differ - e.g. Architecture
 * proposed "UrlShortenerService.java" but ChangePlanner resolved it to the
 * existing "ShortenerService.java" via ClassNameEquivalence).
 */
public record ChangeDecision(
        String architecturePath,
        String resolvedPath,
        ChangeType changeType,
        String matchedExistingClass,
        String reason
) {
}
