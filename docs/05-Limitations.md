# 05 — Limitations & Future Work

An honest account of what this system does not do, and why each gap was accepted rather than closed, given the scope of this submission. Several limitations identified during initial design were subsequently closed during hardening (noted where relevant) — this document reflects the current, post-hardening state.

## 1. No persistence across restarts

`InMemoryWorkflowStore` is a `ConcurrentHashMap` — all run history is lost on restart. Swapping in a JPA-backed store is additive (implement the same two methods, `save`/`findById`/`findAll`), not a redesign, but wasn't done given the scope of this submission. Acceptable for a single-process demonstration; not acceptable for a shared or production deployment.

## 2. No unit tests for `WorkflowEngine` or the agents themselves

Test coverage for the orchestrator's own logic comes from the scenario scripts exercising the real graph against a real LLM — deliberately chosen over mocked unit tests for this submission, because it validates the actual failure modes that matter (real compiler errors, real provider rate limits, real non-deterministic model output), several of which a mocked `WorkflowNode` test would never have surfaced. The trade-off is that `WorkflowEngine`'s transition logic (gate/retry/block edges specifically) isn't independently verified in isolation from an LLM. A production version's single highest-value addition would be `WorkflowEngineTest`-style coverage using fake `WorkflowNode`s for every transition — gate pause/resume, retry exhaustion at exactly the configured bound, block/resolve — decoupled from any real model call.

*(`shortener-service`, the product itself, does have full test coverage: 5 real `@SpringBootTest` integration tests, which the orchestrator's `TestAgent` runs for real on every implementation attempt.)*

## 3. Re-planning is bounded to Testing → Implementation, not routed back to Architecture

The bounded retry loop assumes the *implementation* was wrong and asks for a corrected implementation. It does not detect "the design itself was the problem" and re-invoke `ArchitectureAgent` on a persistent `TESTING` failure — only a human-supplied clarification answer on the `ARCHITECTURE` gate triggers a genuine re-plan (see [04-Design-Decisions.md §9](04-Design-Decisions.md)). A production version would want a heuristic (or a third retry tier) that escalates to Architecture when Implementation retries are exhausted, rather than only rolling back.

## 4. Guardrails are regex-based, not a real SAST/secret-scanning integration

`GuardrailAgent`'s rule set (hardcoded secrets, AWS-key-shaped strings, embedded private keys, raw-IP persistence, duplicate stereotype classes, package/path mismatch) is adequate to demonstrate the block/resolve governance mechanic reliably and reproducibly. It is not a substitute for a real secret-scanning tool (e.g. gitleaks, TruffleHog) or a real static-analysis security tool in a production pipeline — those would be the natural next integration, running as an additional check alongside (not instead of) this project-specific policy layer.

## 5. `JavaSourceAnalysis` is deliberately not a real Java parser

Every structural fact the pipeline reasons about (class kind, superclass, endpoint mappings, public methods, `@Test` methods) is extracted via regex, not an AST. This is a documented, deliberate trade-off: fast, dependency-free, and covers the conventional Spring Boot style this project (and most brownfield targets) actually uses — but it can be fooled by unusual formatting. The real compiler (`TestAgent`'s `compileJava`/`test` run) is the ground-truth backstop for anything this layer misses; several real bugs found during hardening were exactly this class of issue (a regex mistaking a `record`'s canonical-constructor header for a method declaration, for instance) — each was fixed as found, but the underlying trade-off (regex vs. a real parser like JavaParser) remains a deliberate choice, not an oversight. A production version might swap in a real parser if the range of code styles it needs to handle grows beyond what a small regex set can reliably cover.

## 6. Test-impact discovery is heuristic, not exhaustive

`TestImpactAnalyzer` unions naming-convention matching with content-reference scanning (does the test file's source contain the changed class's simple name). This closes the common case reliably but is not a full call-graph analysis — a test that exercises changed behavior only indirectly (e.g. through a mock that never references the real class's simple name in source) would not be discovered. The real Gradle test run remains the final backstop regardless: if a genuinely-impacted test isn't discovered and fixed, it will still fail at `TESTING` and trigger the normal bounded-retry path, just without the benefit of that test's content being in the prompt on the first attempt.

## 7. Single active LLM provider at a time

`app.llm.mode` selects one of `mock`/`groq`/`anthropic` at startup; the system does not run multiple providers concurrently (e.g. for consensus or fallback). The abstraction (`AgentLlmClient`) makes this straightforward to add — a composite client trying providers in sequence — but wasn't required for this submission's scope.

## 8. No authentication or authorization on the orchestrator's REST API

Every endpoint is open. Acceptable for a local demonstration; a shared or production deployment would need at minimum an API key or session-based auth in front of `WorkflowController`, and ideally a real approval UI in front of `approve`/`resolve-block` rather than a raw POST any caller can issue.

## 9. Real LLM calls are inherently non-deterministic and rate-limited

Running the four required scenarios against a live provider (rather than only mock mode) surfaced real-world constraints that a mocked demonstration would hide: per-minute and per-day token quotas, occasional model mistakes that need a retry to correct (an unrelated dependency chosen that isn't on the classpath, a checked exception thrown without being declared). The system is built to absorb both classes of variance — provider-level backoff for throttling, workflow-level bounded retry with feedback for plan-quality issues (see [04-Design-Decisions.md §7](04-Design-Decisions.md)) — but neither eliminates the underlying non-determinism, only bounds its impact. A production deployment against a paid, higher-quota tier would see these far less often.

## 10. Greenfield scenario runs against an already-built product

`scenarios/01-greenfield.sh` demonstrates the full graph against the existing `shortener-service` rather than a genuinely empty repository, to keep the scenario meaningfully different from brownfield while staying within scope — re-deriving the entire product from zero through the pipeline would mostly repeat the same demonstration mechanics as the brownfield scenario. Documented directly in the script's header comment.

## What's next, in priority order

1. `WorkflowEngineTest` covering every transition (gate pause/resume, retry exhaustion at the exact bound, block/resolve) against fake `WorkflowNode`s, independent of any LLM.
2. Escalate to `ArchitectureAgent` after Implementation retries are exhausted, rather than only rolling back — genuine dynamic re-planning per the original brief.
3. JPA-backed `WorkflowState` store so runs survive restarts and are queryable for real fleet-level success-rate reporting.
4. A real secret-scanning/SAST integration alongside the existing project-specific Guardrail rules.
5. Minimal auth in front of the orchestrator's mutating endpoints (`approve`, `resolve-block`, `apply`).
