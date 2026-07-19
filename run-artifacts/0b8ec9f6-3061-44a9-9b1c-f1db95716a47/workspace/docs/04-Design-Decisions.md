# 04 — Design Decisions

This document records the significant decisions behind the system, the alternatives considered, and why the chosen approach won. Each entry follows the same shape: **the decision**, **why it matters**, **what was rejected and why**.

## 1. Hand-rolled graph engine instead of an orchestration framework

**Decision:** `WorkflowEngine` is ~150 lines of plain Java routing over a `Stage` enum — no LangGraph/Spring AI Alibaba Graph/etc.

**Why:** The governance mechanics — gates, bounded retry, rollback, blocking, audit trail — are the artifact actually being evaluated here. A framework would manage those mechanics *for* the code, which means a reviewer inspecting this repository would be reading framework configuration, not engineering. Owning the routing logic directly makes every transition (why a retry happens, why a gate pauses, why a block can't be silently bypassed) fully inspectable in one file, and fully defensible in a review.

**Rejected:** Any LLM-orchestration framework with built-in graph/state machine primitives — would have shipped faster but hidden exactly the part meant to be graded.

## 2. Mock LLM mode as the default

**Decision:** `app.llm.mode: mock` unless explicitly overridden; `MockLlmClient` returns deterministic canned JSON keyed by a `SCHEMA_ID` marker in each prompt.

**Why:** The entire graph — every gate, the retry loop, the guardrail block/resolve path — needs to be demonstrable repeatedly, deterministically, and at zero API cost. A reviewer running the scenario scripts cold, without any provider key configured, still sees the full governance surface exercised correctly.

**Trade-off accepted:** Mock mode cannot demonstrate genuine LLM reasoning (ambiguity detection quality, code generation quality). That's why the four required scenarios in this submission were additionally run and evidenced against a **real** provider (Groq) — see [03-Workflow-Scenarios.md](03-Workflow-Scenarios.md) — rather than relying on mock-mode traces alone.

## 3. Provider-agnostic LLM abstraction (`AgentLlmClient`)

**Decision:** Every agent depends only on `AgentLlmClient.complete(system, user)`. Three implementations (`Mock`, `Groq`, `Anthropic`) are selected purely by Spring configuration (`@ConditionalOnProperty`); no agent code branches on provider.

**Why:** Swapping the model backing this system — for cost, availability, or comparison — is a one-line config change, not a code change. This was validated in practice during this submission's hardening: the system was run against several different Groq keys (and would run identically against Anthropic) with zero changes to any agent.

## 4. `ChangePlanner` is authoritative over the LLM's own CREATE/MODIFY choice

**Decision:** The LLM is never trusted to decide whether a file is new or existing. `ChangePlanner` resolves that deterministically against the real, freshly-indexed workspace before the LLM is even called; `ImplementationValidator` re-verifies the LLM's returned `action` against that same decision after the call.

**Why:** An LLM asked to "design a feature" defaults toward inventing plausible-sounding new class names, even when an equivalent class already exists — this is a well-documented LLM bias (favors the more common training-data pattern of "design a new app" over "extend this specific existing one"). Left unchecked, this produces duplicate classes with duplicate logic. Making the decision deterministic and non-negotiable closes that failure mode structurally rather than by asking the model nicely.

## 5. `ClassNameEquivalence`'s substring fallback is restricted to single-token names

**Decision:** The heuristic that matches "Short vs. Shortener" (catching e.g. `UrlShortenerController` against the real `ShortenController`) only applies its substring check when **both** candidate names reduce to exactly one core token after stripping filler words and role suffixes.

**Why:** An earlier, looser version of this check (any token in one name substring-matching any token in the other) caused a genuinely new, unrelated utility class (`ShortCodeGenerator`) to be incorrectly matched against `ShortenController`, purely because "short" is a substring of "shorten" — a completely generic word fragment, not a meaningful conceptual match. Restricting the fallback to single-token names preserves the intended "reworded the same noun" case while eliminating false positives on multi-token names that only incidentally share one word fragment.

## 6. Documentation and Release stages are templated, not LLM-generated

**Decision:** `DocsAgent` and `ReleaseAgent` never call an LLM. Both render already-structured `WorkflowState` data (spec, design, implementation, test/guardrail results, retry counts, approval history) directly into their outputs.

**Why:** Every field these stages need already exists as typed data on `WorkflowState`. Asking a model to faithfully restate structured data as prose introduces a real risk of paraphrasing error (a model could plausibly "summarize" 4 retries as 2, or omit a flagged ambiguity) for zero benefit — templating is strictly more reliable, and the release checklist in particular needs to be trustworthy precisely because a human is about to sign off based on it.

## 7. Two separate retry layers: workflow-level vs. LLM-client-level

**Decision:** `WorkflowEngine`'s bounded retry (max 2, for "the plan was wrong") and `LlmRetrySupport`'s backoff-and-retry (for "the provider is momentarily throttling us") are implemented independently, at different layers, and never share a counter.

**Why:** These are genuinely different failure modes with different correct responses. A bad plan should be retried *with feedback about what was wrong* — that's a workflow concern, bounded so it can't loop forever. A transient `429` has nothing to do with the plan's quality and needs a short backoff before the *same* call is retried — a client concern. Conflating them was tried first and failed in practice: two workflow-level retries fired back-to-back (the common case when a plan genuinely needs a small fix) reliably landed inside the same one-minute provider rate-limit window, and both failed instantly — burning the entire workflow retry budget on throttling instead of ever giving the actual retry attempt a chance to run. Separating the layers means a transient throttle is absorbed invisibly below the workflow, and the workflow's bounded retry is reserved for genuine plan-quality failures.

## 8. `TestImpactAnalyzer` — bridging Implementation's blind spot on test files

**Decision:** Before the LLM call, discover existing test files impacted by any `MODIFY` decision (via naming convention **and** content reference), include their full content in the prompt with explicit "fix only what's broken, preserve test intent" instructions, and let `ImplementationValidator` treat them as legitimate `MODIFY` targets — including a check that no `@Test` method is silently deleted rather than fixed.

**Why:** `ImplementationAgent` was originally scoped to production source only. This is architecturally correct for most changes, but breaks down for the specific (common) case where a change alters a public constructor or method signature that an existing test calls directly — the agent has no way to know that test exists, let alone that it needs updating, so the run reproducibly fails at `TESTING` with a compile error the model could never have anticipated. This was confirmed as a **100%-reproducible** failure mode (not LLM variance) during hardening of [Scenario 2](03-Workflow-Scenarios.md#scenario-2--brownfield): any reasonable implementation of "custom alias support" adds a field to the request record, which changes its canonical constructor, which breaks the pre-existing integration test's positional constructor call, every time.

**Rejected alternative:** Giving `ImplementationAgent` unrestricted visibility into all test files. Rejected for two reasons: (1) unbounded prompt growth as the test suite grows, and (2) it invites the model to "fix" a failure by weakening or deleting test coverage rather than fixing the actual call site. The chosen design bounds the prompt to only the tests actually referencing changed code, and the validator's dropped-`@Test`-method check is the mechanical enforcement of "fix, don't delete."

## 9. Scenario scripts use a bounded approval loop, not a single conditional check

**Decision:** `03-ambiguous.sh` loops on `PENDING_APPROVAL` (bounded to 5 iterations) rather than a single `if status == PENDING_APPROVAL, approve once`.

**Why:** The `AMBIGUOUS` scenario can require **two** separate gate approvals at `ARCHITECTURE` — the first triggers the dynamic re-plan (§ [02-Agent-Orchestration.md §2](02-Agent-Orchestration.md#2-human-approval-gates)), and only the *second* approval (of the freshly re-planned design) actually cascades onward to `RELEASE`. A single conditional check stops one gate short of `COMPLETED`, even though every underlying mechanic worked correctly — the script's assumption, not the orchestrator's behavior, was the gap.

## 10. Fail-fast validation of `app.codebase.repo-root` at startup

**Decision:** `WorkspaceService` validates at `@PostConstruct` that `repo-root` resolves to a directory actually containing `shortener-service/build.gradle`, and refuses to start with an actionable error otherwise.

**Why:** `repo-root` is a path relative to the process's working directory — correct and automatic under `./gradlew :agent-orchestrator:bootRun` (Gradle sets this explicitly), but silently wrong if the process is launched any other way with a different working directory (e.g. a raw `java -jar`, or certain IDE run configurations). Without this check, a wrong working directory doesn't fail immediately — it silently corrupts every subsequent run-artifacts path, and the first visible symptom arrives minutes later, deep inside a `TESTING` stage, as a confusing `'gradlew.bat' is not recognized` error. Failing at startup, with a message naming the exact mismatch and the fix, converts a multi-minute misdiagnosis into an immediate, obvious one.

## 11. IDE launch parity via `.run/` config, not a code-level path resolver

**Decision:** When IntelliJ's default "green Run button" configuration was found to resolve a different working directory than `./gradlew bootRun`, the fix was a checked-in `.run/OrchestratorApplication.run.xml` (IntelliJ's shared run-configuration mechanism) explicitly pinning `WORKING_DIRECTORY`, not a change to how `repo-root` is resolved in code.

**Why:** The actual defect was environmental (a missing explicit setting in the IDE's run configuration), not a logic error in the application — `repo-root`'s relative-path contract is simple, well-documented, and correct; it just needs the working directory it assumes to actually be true. Introducing a more complex, launch-context-independent root resolver (e.g. deriving it from the running JAR's own location) would have solved a problem that a one-line IDE configuration already solves, at the cost of new code with its own edge cases. Matching the fix to the actual size of the problem was the deliberate choice here.
