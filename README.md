# URL Shortener + Agentic SDLC Orchestrator

## Project Overview

A URL shortener product, built and evolved not by hand but by a governed, agentic orchestration layer that runs a real software development lifecycle — requirements → architecture → implementation → testing → guardrails → documentation → release — as an auditable graph with human approval gates, not a linear script.

Two independently runnable Spring Boot applications in one Gradle build:

- **`shortener-service`** — the product: a URL shortener REST API (create, redirect, click-count stats).
- **`agent-orchestrator`** — the orchestration layer: reads the product's real source tree, plans changes with an LLM, writes and validates them in an isolated per-run workspace, runs the product's real test suite, and only ever touches the live product after every gate is explicitly human-approved.

## High-Level Flow

```
                  User
                  │
                  ▼
          Workflow Controller
                  │
                  ▼
           Workflow Engine
                  │
                  ▼
            Workflow State
                  │
 ┌─────────────────────────────────┐
 │ Requirements Agent              │
 │ Architecture Agent              │
 │ Implementation Agent            │
 │ Testing Agent                   │
 │ Guardrail Agent                 │
 │ Documentation Agent             │
 │ Release Agent                   │
 └─────────────────────────────────┘
                  │
                  ▼
            Workspace Copy
                  │
                  ▼
           Generated Project
```

A request enters through `WorkflowController`, which delegates all routing to `WorkflowEngine`. The engine drives one shared `WorkflowState` through the seven agents in sequence (with gates, retries, and blocks along the way — see [`docs/02-Agent-Orchestration.md`](docs/02-Agent-Orchestration.md)), acting only on an isolated workspace copy of the product, never the live tree directly. See [`docs/07-Diagrams.md`](docs/07-Diagrams.md) for the full set of detailed diagrams (sequence, state lifecycle, per-scenario flows, retry mechanism).

## Features

- **Governed SDLC graph** — 7 stages (Requirements, Architecture, Implementation, Testing, Guardrails, Documentation, Release), each backed by a real agent, with 3 human approval gates.
- **Bounded, feedback-carrying retry** — a failed implementation attempt is retried (up to 2×) with the prior failure's exact context, then safely rolled back rather than looping forever.
- **Deterministic guardrails** — real static-analysis checks (secrets, key-shaped strings, duplicate classes, package integrity) that can block a run pending explicit human resolution.
- **Workspace isolation** — every run operates on its own full copy of the repo; the live product is untouched until an explicit, gated `apply` call.
- **Test-impact awareness** — a production change that alters an existing class's public shape automatically pulls in the tests that reference it, so they get fixed alongside the code that broke them.
- **Full audit trail & metrics** — an append-only decision log per run, plus computed retry counts, gate-pass counts, and mean-time-to-recovery.
- **Provider-agnostic LLM layer** — mock (deterministic, zero-cost), Groq, and Anthropic backends behind one interface; swapping providers is a config change, not a code change.

## Technology Stack

- **Language / Runtime:** Java 17
- **Framework:** Spring Boot 3.5 (Web, Validation, JPA)
- **AI Integration:** Spring AI (Anthropic + OpenAI-compatible clients, used for Groq)
- **Persistence (product):** JPA / H2
- **Build:** Gradle (multi-module)
- **Testing:** JUnit 5, Spring Boot Test

## Project Structure

```
url-shortener-agentic/
├── agent-orchestrator/               the orchestration layer (:8081)
│   └── src/main/java/com/example/orchestrator/
│       ├── agents/                   RequirementsAgent, ArchitectureAgent, ImplementationAgent,
│       │                             TestAgent, GuardrailAgent, DocsAgent, ReleaseAgent
│       ├── graph/                    WorkflowEngine, WorkflowNode, StageResult, NodeOutcome
│       ├── state/                    WorkflowState, Stage, RunStatus, Actor, DecisionRecord
│       ├── implementation/           ProjectIndexer, ChangePlanner, ClassNameEquivalence,
│       │                             TestImpactAnalyzer, ImplementationValidator, JavaSourceAnalysis
│       ├── llm/                      AgentLlmClient, MockLlmClient, GroqLlmClient,
│       │                             AnthropicLlmClient, LlmRetrySupport
│       ├── codebase/                 WorkspaceService, CodebaseContextService
│       ├── controller/                WorkflowController + request/response DTOs
│       ├── audit/                     AuditLogger
│       ├── metrics/                   MetricsCollector, RunMetrics
│       └── repository/                InMemoryWorkflowStore
├── shortener-service/                 the product (:8080)
│   └── src/main/java/com/example/shortener/
│       ├── controller/, service/, repository/, model/, dto/, exception/
├── scenarios/                         01-greenfield.sh … 04-governance-demo.sh
├── docs/                              01-Architecture.md … 07-Diagrams.md
├── run-artifacts/                     generated per-run isolated workspaces + summaries
├── .run/                              checked-in IntelliJ run configuration
└── build.gradle, settings.gradle      multi-module Gradle build
```

## Setup Instructions

**Prerequisites:** Java 17, Gradle (or your IDE's bundled Gradle), `curl` + `python3` (only for the scenario demo scripts).

```bash
git clone <this-repo>
cd url-shortener-agentic
./gradlew build
```

**LLM provider (optional):** the orchestrator defaults to `app.llm.mode: mock` — no key needed, fully deterministic. To use a real model, set `ANTHROPIC_API_KEY` or `GROQ_API_KEY` and change `app.llm.mode` in `agent-orchestrator/src/main/resources/application.yml`.

## How to Run

```bash
./gradlew :shortener-service:bootRun       # product, on :8080
./gradlew :agent-orchestrator:bootRun      # orchestrator, on :8081
```

> **IntelliJ users:** always launch via the Gradle task above (or the checked-in `.run/OrchestratorApplication.run.xml` configuration), not a bare `java` run on `OrchestratorApplication` — Gradle sets the module working directory that `app.codebase.repo-root` depends on.

Interact with the orchestrator via its REST API (Postman, curl, or the scenario scripts below):

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/workflow/runs` | Start a run |
| POST | `/api/v1/workflow/runs/{id}/approve` | Approve the current gate |
| POST | `/api/v1/workflow/runs/{id}/resolve-block` | Resolve a guardrail block |
| POST | `/api/v1/workflow/runs/{id}/apply` | Copy a `COMPLETED` run's changes into the live product |
| GET | `/api/v1/workflow/runs/{id}` | Full run state |
| GET | `/api/v1/workflow/runs/{id}/audit` | Full decision log |
| GET | `/api/v1/workflow/runs/{id}/metrics` | Reliability metrics for the run |

**Example payloads** — `POST /runs`, one per scenario:

```jsonc
// Greenfield
{ "scenarioType": "GREENFIELD", "rawRequirement": "Build a URL shortener service from scratch: create short links, redirect to the original URL, and track click counts." }

// Brownfield
{ "scenarioType": "BROWNFIELD", "rawRequirement": "Add support for user-supplied custom aliases when creating a short URL, instead of only auto-generated codes. Reject aliases that are already taken." }

// Ambiguous
{ "scenarioType": "AMBIGUOUS", "rawRequirement": "Make the analytics better." }

// Governance demo — bounded retry
{ "scenarioType": "GREENFIELD", "rawRequirement": "DEMO_RETRY_TRIGGER: add a health check endpoint." }

// Governance demo — guardrail block (mock mode only)
{ "scenarioType": "GREENFIELD", "rawRequirement": "DEMO_GUARDRAIL_TRIGGER: add a config helper class." }
```

`POST /runs/{id}/approve` — same shape for every scenario:
```json
{ "approvedBy": "reviewer", "notes": "looks good" }
```

## Demo Scenarios

With the orchestrator running, from another terminal:

```bash
cd scenarios
./01-greenfield.sh      # build-from-scratch style request
./02-brownfield.sh      # real change against the existing codebase
./03-ambiguous.sh       # vague ask -> requirements agent surfaces ambiguity, human clarifies
./04-governance-demo.sh # deterministic retry-loop + guardrail-block demonstration
```

Each script prints the run's full state, audit log, and metrics at every step. See [`docs/03-Workflow-Scenarios.md`](docs/03-Workflow-Scenarios.md) for real, evidenced run excerpts of all four.

## Documentation Links

Detailed engineering documentation lives in [`docs/`](docs/):

- [`docs/01-Architecture.md`](docs/01-Architecture.md) — system overview, component map, the stage graph, workspace isolation model
- [`docs/02-Agent-Orchestration.md`](docs/02-Agent-Orchestration.md) — the engine, retry mechanism, guardrails, human approval gates, and every agent in depth
- [`docs/03-Workflow-Scenarios.md`](docs/03-Workflow-Scenarios.md) — the four required scenarios, with real run evidence
- [`docs/04-Design-Decisions.md`](docs/04-Design-Decisions.md) — key decisions, alternatives considered, and why
- [`docs/05-Limitations.md`](docs/05-Limitations.md) — known limitations, trade-offs, and prioritized future work
- [`docs/06-Engineering-Journal.md`](docs/06-Engineering-Journal.md) — design challenges, key decisions, and issues resolved during development
- [`docs/07-Diagrams.md`](docs/07-Diagrams.md) — consolidated Mermaid diagrams: architecture, sequence, state lifecycle, per-scenario flows, workspace isolation, retry mechanism, approval gates
