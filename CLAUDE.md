# url-shortener-agentic

Two-module Gradle/Java 17 project:
- `shortener-service` — the product (Spring Boot URL shortener), port 8080
- `agent-orchestrator` — the agentic SDLC orchestration layer, port 8081

## Build & run
```
./gradlew build
./gradlew :shortener-service:test
./gradlew :agent-orchestrator:bootRun
```

## LLM provider
`agent-orchestrator/src/main/resources/application.yml` -> `app.llm.mode`:
- `mock` — canned responses, no API calls, safe to run repeatedly
- `groq` — real calls via Groq (OpenAI-compatible), requires `GROQ_API_KEY` env var
- `anthropic` — real Claude calls, requires `ANTHROPIC_API_KEY` env var

## Running the three required scenarios
With the orchestrator running on :8081:
```
cd scenarios
./01-greenfield.sh
./02-brownfield.sh
./03-ambiguous.sh
./04-governance-demo.sh   # deterministic retry-loop + guardrail-block demo
```

## Key architecture facts
- Every run gets an isolated workspace at `run-artifacts/{runId}/workspace/` —
  Implementation/Testing/Guardrails all operate there, NEVER on the live
  `shortener-service` module directly.
- `POST /api/v1/workflow/runs/{id}/apply` is the only action that touches the
  live product, and only works once a run's status is COMPLETED.
- See `ARCHITECTURE.md` and `FINAL_ENGINEERING_SUMMARY.md` for full design
  rationale, known limitations, and assumptions.

## When compilation or tests fail
Read the actual gradle output, don't guess. `ImplementationValidator`,
`ChangePlanner`, and `JavaSourceAnalysis` in
`agent-orchestrator/.../implementation/` are regex-based, not a real parser —
if something fails in a way that looks like a parsing edge case rather than a
real bug, check there first.
