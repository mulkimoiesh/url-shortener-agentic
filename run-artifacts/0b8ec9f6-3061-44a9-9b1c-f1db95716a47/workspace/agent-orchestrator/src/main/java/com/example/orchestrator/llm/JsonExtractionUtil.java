package com.example.orchestrator.llm;

/**
 * Models occasionally wrap JSON in ```json fences or add stray whitespace
 * even when explicitly told not to. This makes parsing robust to that
 * instead of failing the whole stage on a formatting quirk.
 */
public final class JsonExtractionUtil {

    private JsonExtractionUtil() {
    }

    public static String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        String trimmed = raw.trim();

        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
