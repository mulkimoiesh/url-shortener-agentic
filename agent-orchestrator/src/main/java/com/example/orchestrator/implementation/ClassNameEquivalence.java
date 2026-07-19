package com.example.orchestrator.implementation;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Answers "is this proposed new class actually the same concept as an
 * existing one under a different name?" - e.g. UrlShortenerService vs
 * ShortenerService, or ShortUrlRepository vs UrlShortenerRepository.
 *
 * This is deliberately a heuristic, not exact NLP matching: split each name
 * into lowercase camelCase tokens, drop generic filler words (url/api/rest/
 * web) and the trailing "role" word (Service/Controller/Repository/etc,
 * usually redundant with ClassKind), then compare what's left. Two names
 * match if their remaining core-token sets are equal, or if either set's
 * tokens are substrings of the other's (covers Short vs Shortener). This
 * will have false negatives on truly unrelated naming (acceptable - worst
 * case is an extra CREATE that Implementation's own duplicate-class check
 * catches) but is tuned to catch the common "reworded the same noun" case
 * that caused the original bug.
 */
public final class ClassNameEquivalence {

    private static final Pattern CAMEL_CASE_SPLIT =
            Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    private static final Set<String> FILLER_WORDS = Set.of("url", "api", "rest", "web", "the", "a");
    private static final Set<String> ROLE_WORDS = Set.of(
            "controller", "service", "repository", "repo", "impl", "dto",
            "entity", "exception", "config", "configuration", "handler");

    private ClassNameEquivalence() {
    }

    public static boolean areEquivalent(String nameA, String nameB) {
        if (nameA == null || nameB == null) {
            return false;
        }
        if (nameA.equalsIgnoreCase(nameB)) {
            return true;
        }
        Set<String> coreA = coreTokens(nameA);
        Set<String> coreB = coreTokens(nameB);
        if (coreA.isEmpty() || coreB.isEmpty()) {
            return false;
        }
        if (coreA.equals(coreB)) {
            return true;
        }
        // fallback: single-concept names where one is a substring of the other
        // (catches "short" vs "shortener"). Only meaningful when BOTH names
        // reduce to exactly one core token - a multi-token name incidentally
        // sharing one word fragment with an unrelated class (e.g.
        // "ShortCodeGenerator" vs "ShortenController", both containing
        // "short"/"shorten") is not the same class.
        if (coreA.size() == 1 && coreB.size() == 1) {
            String a = coreA.iterator().next();
            String b = coreB.iterator().next();
            if (a.length() >= 3 && b.length() >= 3 && (a.contains(b) || b.contains(a))) {
                return true;
            }
        }
        return false;
    }

    /** Finds the first existing class considered equivalent to a proposed new class of the given (guessed) kind. */
    public static Optional<ClassInfo> findEquivalent(String proposedSimpleName, ClassKind proposedKind, ProjectIndex index) {
        List<ClassInfo> candidates = proposedKind == ClassKind.OTHER
                ? index.classes()                 // unknown role - cast a wide net
                : index.byKind(proposedKind);      // same role - narrow, more precise
        return candidates.stream()
                .filter(c -> areEquivalent(proposedSimpleName, c.simpleName()))
                .findFirst();
    }

    /** Guesses a class's role from its proposed filename alone, before any content exists to inspect. */
    public static ClassKind guessKindFromName(String simpleName) {
        if (simpleName == null) {
            return ClassKind.OTHER;
        }
        String lower = simpleName.toLowerCase();
        if (lower.endsWith("controller")) return ClassKind.CONTROLLER;
        if (lower.endsWith("service") || lower.endsWith("serviceimpl")) return ClassKind.SERVICE;
        if (lower.endsWith("repository") || lower.endsWith("repo")) return ClassKind.REPOSITORY;
        if (lower.endsWith("exception")) return ClassKind.EXCEPTION;
        if (lower.endsWith("configuration") || lower.endsWith("config")) return ClassKind.CONFIGURATION;
        if (lower.endsWith("dto") || lower.endsWith("request") || lower.endsWith("response")) return ClassKind.DTO;
        return ClassKind.OTHER;
    }

    private static Set<String> coreTokens(String name) {
        String[] rawTokens = CAMEL_CASE_SPLIT.split(name);
        Set<String> result = new LinkedHashSet<>();
        for (String t : rawTokens) {
            String lower = t.toLowerCase();
            if (FILLER_WORDS.contains(lower) || ROLE_WORDS.contains(lower)) {
                continue;
            }
            result.add(lower);
        }
        return result;
    }
}
