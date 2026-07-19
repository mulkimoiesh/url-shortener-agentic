# 03 — Workflow Scenarios

The four scenarios below are the required demonstration set (`scenarios/*.sh`). Every excerpt in this document is a **real, trimmed decision-log excerpt from an actual run against a live LLM** (Groq, `llama-3.3-70b-versatile`) — not a hypothetical trace. Each script prints the full run state, audit log, and (where relevant) metrics; what follows is the signal extracted from that output.

To run these yourself:
```bash
./gradlew :agent-orchestrator:bootRun     # start the orchestrator on :8081
cd scenarios
./01-greenfield.sh
./02-brownfield.sh
./03-ambiguous.sh
./04-governance-demo.sh
```

---

## Scenario 1 — Greenfield

**Demonstrates:** the full graph end-to-end — requirements → architecture → implementation → real test execution → guardrails → docs → release — against a build-from-scratch style request.

**Request:** *"Build a URL shortener service from scratch: create short links, redirect to the original URL, and track click counts."*

**Real run excerpt:**
```
Run started. Scenario=GREENFIELD
Generated spec: 5 functional, 4 non-functional requirements, 3 clarification question(s).
Approved by interview-demo. Spec looks complete for a greenfield build.
Design produced (GREENFIELD): 5 impacted file(s), 3 API endpoint(s), 4 design decisions.
Approved by interview-demo. Design accepted.
ChangePlanner decided (deterministically, before any LLM call): 3 CREATE, 2 MODIFY (2 redirected to an existing equivalent class), 0 SKIP.
Wrote 5 file(s) to workspace (attempt 0): [ShortenerServiceApplication.java, ShortenController.java, UrlMapping.java, UrlMappingRepository.java, UrlShorteningService.java]
gradlew test (workspace) exit code 0 (PASSED)
No policy violations found in 5 changed file(s).
Run summary written to run-artifacts/{runId}/SUMMARY.md
All automated checks passed - awaiting final human sign-off.
Approved by interview-demo. All checks green, approving release.
```
**Result:** `status: COMPLETED`, 0 retries, real Gradle test suite passed on the first attempt.

Note the `2 MODIFY (2 redirected to an existing equivalent class)` line — even on a "build from scratch" request, `ChangePlanner`/`ClassNameEquivalence` correctly identified that the requested controller already existed under an equivalent name in the pre-built product, and redirected the LLM to modify it instead of generating a duplicate. This is the exact mechanism documented in [02-Agent-Orchestration.md §6.3](02-Agent-Orchestration.md#63-implementationagent--the-deep-pipeline).

---

## Scenario 2 — Brownfield

**Demonstrates:** a real, targeted change against the existing codebase, where `ArchitectureAgent` reads the actual source tree to identify impacted files instead of guessing — and where the change genuinely requires touching an existing test file.

**Request:** *"Add support for user-supplied custom aliases when creating a short URL, instead of only auto-generated codes. Reject aliases that are already taken."*

**Real run excerpt:**
```
Run started. Scenario=BROWNFIELD
Generated spec: 4 functional, 2 non-functional requirements, 3 clarification question(s).
Approved by interview-demo. Approved - alias charset restricted to [a-zA-Z0-9-_], max 30 chars, enforced by validation.
Design produced (BROWNFIELD): 5 impacted file(s), 3 API endpoint(s), 1 design decisions.
Approved by interview-demo. Design accepted - impacted files correctly identified.
ChangePlanner decided (deterministically, before any LLM call): 0 CREATE, 5 MODIFY (5 redirected to an existing equivalent class), 0 SKIP.
Discovered 1 existing test file(s) referencing modified classes - included for update:
    [src/test/java/com/example/shortener/ShortenerServiceIntegrationTest.java]
Wrote 6 file(s) to workspace (attempt 0):
    [ShortenController.java, ShortenRequest.java, ShortenerService.java, ShortUrlRepository.java,
     GlobalExceptionHandler.java, ShortenerServiceIntegrationTest.java]
gradlew test (workspace) exit code 0 (PASSED)
No policy violations found in 6 changed file(s).
Run summary written to run-artifacts/{runId}/SUMMARY.md
All automated checks passed - awaiting final human sign-off.
Approved by interview-demo. All checks green.
```
**Result:** `status: COMPLETED`, **0 retries**, all 5 production files modified in place (0 unnecessary creates), the pre-existing integration test correctly discovered and updated in the same attempt, real Gradle test suite passed.

This scenario is the one that most exercises `TestImpactAnalyzer` (see [02-Agent-Orchestration.md §6.3](02-Agent-Orchestration.md#63-implementationagent--the-deep-pipeline)): adding a `customAlias` field to the request DTO changes its constructor shape, which the pre-existing integration test calls positionally. Without test-impact discovery, this class of change reproducibly fails at `TESTING` with a compile error the model has no way to see coming (`ShortenRequest` constructor mismatch) — with it, the same file is included as a `MODIFY` target and fixed in the same attempt that made the production change.

---

## Scenario 3 — Ambiguous

**Demonstrates:** the Requirements Agent surfacing genuine ambiguity rather than silently guessing, and the engine's dynamic re-planning path — re-running `ARCHITECTURE` once a human supplies a clarifying answer, instead of advancing with an empty design.

**Request:** *"Make the analytics better."*

**Real run excerpt:**
```
Run started. Scenario=AMBIGUOUS
Generated spec: 0 functional, 0 non-functional requirements, 3 clarification question(s)
    - review these before approving the gate.
Approved by interview-demo. Clarifying: 'better' = add a per-day click count breakdown to the
    stats endpoint. Out of scope: dashboards, real-time streaming, data export.
Design deferred pending clarification: [What specific aspects of analytics need improvement...,
    What are the KPIs..., Are there specific tools/features..., How will 'better' be defined...,
    What is the current state of analytics...]
Approved by interview-demo. Design accepted given the clarified scope.
Clarification received - re-running ARCHITECTURE instead of advancing with an empty design.
Design produced (AMBIGUOUS): 1 impacted file(s), 3 API endpoint(s), 1 design decisions.
Approved by interview-demo. All checks green.
ChangePlanner decided (deterministically, before any LLM call): 0 CREATE, 1 MODIFY
    (1 redirected to an existing equivalent class), 0 SKIP.
Wrote 1 file(s) to workspace (attempt 0): [ShortenerService.java]
gradlew test (workspace) exit code 0 (PASSED)
No policy violations found in 1 changed file(s).
All automated checks passed - awaiting final human sign-off.
Approved by interview-demo. All checks green.
```
**Result:** `status: COMPLETED`. Note the sequence: the Requirements Agent produced **zero** functional requirements and three clarification questions rather than guessing what "better" means; the first `ARCHITECTURE` pass correctly deferred (matching `clarificationQuestions` non-empty, `impactedFiles` empty); the human's clarifying answer triggered a genuine re-run of `ARCHITECTURE` (not just a re-approval of the same design); the second pass produced a real, scoped design. This required **two separate gate approvals at `ARCHITECTURE`** — the first triggers the re-plan, the second approves its output — which the demo script handles with a bounded approval loop rather than a single conditional check (see [04-Design-Decisions.md §9](04-Design-Decisions.md)).

---

## Scenario 4 — Governance Demo

**Demonstrates:** the bounded retry loop and the guardrail block mechanic, made reproducible on demand via two explicit, clearly-labeled demo triggers (`DEMO_RETRY_TRIGGER`, `DEMO_GUARDRAIL_TRIGGER` — documented in `TestAgent.java` / `MockLlmClient.java`) rather than left to chance.

### Part A — Bounded retry → eventual success

**Request:** *"DEMO_RETRY_TRIGGER: add a health check endpoint."*

**Real run excerpt:**
```
Wrote 3 file(s) to workspace (attempt 0): [HealthCheckController.java, HealthCheckService.java, Application.java]
[DEMO] Synthetic failure to exercise the bounded-retry loop deterministically.
    Real gradle test was not invoked on this attempt. Triggered by 'DEMO_RETRY_TRIGGER'.
Tests failed - retrying IMPLEMENTATION (attempt 1 of 2)
Wrote 2 file(s) to workspace (attempt 1): [HealthCheckService.java, Application.java]
[DEMO] Synthetic failure to exercise the bounded-retry loop deterministically.
Tests failed - retrying IMPLEMENTATION (attempt 2 of 2)
Wrote 2 file(s) to workspace (attempt 2): [HealthCheckService.java, Application.java]
gradlew test (workspace) exit code 0 (PASSED)
No policy violations found in 2 changed file(s).
All automated checks passed - awaiting final human sign-off.
```
**Result:** exactly the documented sequence — **FAILURE → retry 1 → FAILURE → retry 2 → SUCCESS** — `totalRetries: 2` in the run's metrics, matching `MAX_IMPLEMENTATION_RETRIES` precisely (not 3; see [02-Agent-Orchestration.md §3.1](02-Agent-Orchestration.md#31-workflow-level-bounded-retry--the-plan-was-wrong) for why the boundary matters). The real Gradle test suite ran for the first time only on the third attempt and passed.

### Part B — Guardrail catches a planted secret

**Request:** *"DEMO_GUARDRAIL_TRIGGER: add a config helper class."*

`DEMO_GUARDRAIL_TRIGGER` is handled inside `MockLlmClient` specifically — it makes the *mock* Implementation Agent emit a file containing an AWS-key-shaped string, so `GuardrailAgent`'s regex genuinely catches it and the run transitions to `BLOCKED`. Run this part with `app.llm.mode: mock` for the deterministic block-and-resolve demonstration described in the script.

Run against a real LLM instead (as the evidence below shows), the trigger string has no special meaning to the model — it does not plant a secret, so `GuardrailAgent` correctly finds nothing to flag and the run proceeds normally:
```
Wrote 4 file(s) to workspace (attempt 0): [ConfigHelper.java, ConfigHelperImpl.java, ConfigSource.java, ConfigException.java]
compileJava failed (exit code 1)
Tests failed - retrying IMPLEMENTATION (attempt 1 of 2)
Wrote 4 file(s) to workspace (attempt 1): [ConfigHelper.java, ConfigHelperImpl.java, ConfigSource.java, ConfigException.java]
gradlew test (workspace) exit code 0 (PASSED)
No policy violations found in 4 changed file(s).
All automated checks passed - awaiting final human sign-off.
```
This is the correct, expected behavior for this mode — not a defect. (This run also incidentally shows the workflow-level retry recovering from an ordinary LLM code-quality mistake — a checked exception thrown without being declared/caught — on the very next attempt.)

---

## Summary Table

| Scenario | Gates approved | Retries | Result | Key mechanic demonstrated |
|---|---|---|---|---|
| 1 — Greenfield | 3 | 0 | COMPLETED | Full graph, `ClassNameEquivalence` redirect |
| 2 — Brownfield | 3 | 0 | COMPLETED | Real codebase reasoning, `TestImpactAnalyzer` |
| 3 — Ambiguous | 4 (2× ARCHITECTURE) | 0 | COMPLETED | Ambiguity surfacing, dynamic re-planning |
| 4A — Retry demo | 2 | 2 | COMPLETED | Bounded retry, exact boundary |
| 4B — Guardrail demo | — | 1 | COMPLETED (mock: BLOCKED) | Guardrail block/resolve (mock-mode only) |
