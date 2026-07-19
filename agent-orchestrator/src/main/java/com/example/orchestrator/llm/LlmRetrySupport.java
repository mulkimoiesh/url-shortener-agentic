package com.example.orchestrator.llm;

import java.util.function.Supplier;

/**
 * Shared retry-with-backoff for transient provider throttling (HTTP 429 / rate limits) -
 * distinct from WorkflowEngine's own bounded IMPLEMENTATION retry, which exists for "the LLM
 * produced a bad plan," not for the provider being momentarily unavailable. Without this, two
 * workflow-level retries fired back-to-back (the common case when a plan needs fixing) land in
 * the same per-minute rate-limit window and both fail immediately, burning the whole retry
 * budget without ever giving the LLM call itself a chance to succeed.
 */
final class LlmRetrySupport {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 2000;

    private LlmRetrySupport() {
    }

    static String callWithBackoff(Supplier<String> call) {
        RuntimeException lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                lastError = e;
                if (!isTransientRateLimit(e) || attempt == MAX_ATTEMPTS - 1) {
                    throw e;
                }
                sleep(BASE_BACKOFF_MS * (attempt + 1));
            }
        }
        throw lastError;
    }

    private static boolean isTransientRateLimit(RuntimeException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("429") || message.toLowerCase().contains("rate_limit"));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
