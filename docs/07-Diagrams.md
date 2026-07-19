# 06 — Diagrams

A consolidated visual reference for the system. Each diagram is intentionally self-contained with a short caption — for the full narrative behind any of them, see [01-Architecture.md](01-Architecture.md), [02-Agent-Orchestration.md](02-Agent-Orchestration.md), or [03-Workflow-Scenarios.md](03-Workflow-Scenarios.md).

## 1. Overall System Architecture

```mermaid
graph TB
    subgraph Client
        UI[Postman / curl / scenario scripts]
    end

    subgraph "agent-orchestrator :8081"
        Controller[WorkflowController]
        Engine[WorkflowEngine]
        State[(WorkflowState)]
        Store[(InMemoryWorkflowStore)]
        Audit[AuditLogger]
        Metrics[MetricsCollector]

        subgraph "Agents (WorkflowNode)"
            Req[RequirementsAgent]
            Arch[ArchitectureAgent]
            Impl[ImplementationAgent]
            Test[TestAgent]
            Guard[GuardrailAgent]
            Docs[DocsAgent]
            Rel[ReleaseAgent]
        end

        subgraph "Implementation Pipeline"
            Indexer[ProjectIndexer]
            Planner[ChangePlanner]
            Equiv[ClassNameEquivalence]
            TestImpact[TestImpactAnalyzer]
            PromptBuilder[ImplementationPromptBuilder]
            Validator[ImplementationValidator]
        end

        subgraph "LLM Layer"
            LlmIface{{AgentLlmClient}}
            Mock[MockLlmClient]
            Groq[GroqLlmClient]
            Anthropic[AnthropicLlmClient]
            Backoff[LlmRetrySupport]
        end

        Workspace[WorkspaceService]
        Codebase[CodebaseContextService]
    end

    subgraph "shortener-service :8080  (the product)"
        Product[(source tree + real Gradle test suite)]
    end

    UI --> Controller
    Controller --> Engine
    Controller --> Store
    Engine --> State
    Engine --> Audit
    Engine --> Metrics
    Engine --> Req & Arch & Impl & Test & Guard & Docs & Rel

    Req --> LlmIface
    Arch --> LlmIface
    Arch --> Codebase
    Impl --> Indexer --> Planner --> Equiv
    Planner --> TestImpact --> PromptBuilder --> LlmIface
    LlmIface --> Validator --> Workspace

    LlmIface --> Mock
    LlmIface --> Groq --> Backoff
    LlmIface --> Anthropic --> Backoff

    Test --> Workspace
    Guard --> Workspace
    Docs --> Codebase

    Workspace -. "isolated copy per run" .-> Product
    Controller -->|"POST /apply  (COMPLETED only)"| Product
```

**Caption:** the orchestrator never depends on the product as a library — it reads/writes its source tree as data and shells out to run its real test suite. Every agent reaches the LLM only through one interface; only `ImplementationAgent` has an internal deterministic pipeline around its single model call.

---

## 2. Workflow Sequence (Happy Path)

```mermaid
sequenceDiagram
    participant H as Human
    participant C as WorkflowController
    participant E as WorkflowEngine
    participant R as RequirementsAgent
    participant A as ArchitectureAgent
    participant I as ImplementationAgent
    participant T as TestAgent
    participant G as GuardrailAgent
    participant D as DocsAgent
    participant Rl as ReleaseAgent

    H->>C: POST /runs {rawRequirement, scenarioType}
    C->>E: startRun()
    E->>R: execute(state)
    R-->>E: SUCCESS (spec)
    Note over E: gate — pause PENDING_APPROVAL
    E-->>H: pendingGate = REQUIREMENTS

    H->>C: POST /runs/{id}/approve
    C->>E: approveGate()
    E->>A: execute(state)
    A-->>E: SUCCESS (design)
    Note over E: gate — pause PENDING_APPROVAL
    E-->>H: pendingGate = ARCHITECTURE

    H->>C: POST /runs/{id}/approve
    C->>E: approveGate()
    E->>I: execute(state)
    I-->>E: SUCCESS (files written)
    E->>T: execute(state)
    T-->>E: SUCCESS (real gradle test passed)
    E->>G: execute(state)
    G-->>E: SUCCESS (no violations)
    E->>D: execute(state)
    D-->>E: SUCCESS (SUMMARY.md written)
    E->>Rl: execute(state)
    Rl-->>E: SUCCESS (checklist)
    Note over E: gate — pause PENDING_APPROVAL
    E-->>H: pendingGate = RELEASE

    H->>C: POST /runs/{id}/approve
    C->>E: approveGate()
    E-->>H: status = COMPLETED

    H->>C: POST /runs/{id}/apply
    C-->>H: files copied into live shortener-service
```

**Caption:** one HTTP request can cascade through several non-gate stages before returning — approving `ARCHITECTURE` here carries the run all the way to the `RELEASE` gate in a single call.

---

## 3. WorkflowState Lifecycle

```mermaid
stateDiagram-v2
    [*] --> RUNNING: new WorkflowState()\n(status=RUNNING, stage=REQUIREMENTS)

    RUNNING --> PENDING_APPROVAL: gate stage succeeds\n(REQUIREMENTS / ARCHITECTURE / RELEASE)
    PENDING_APPROVAL --> RUNNING: approveGate()

    RUNNING --> BLOCKED: GUARDRAILS returns BLOCKED
    BLOCKED --> RUNNING: resolveBlock()

    RUNNING --> RUNNING: non-gate stage succeeds\n(advance to next stage)
    RUNNING --> RUNNING: TESTING/IMPLEMENTATION fails,\nretries < MAX (2) — loop to IMPLEMENTATION

    RUNNING --> FAILED: retries exhausted (→ ROLLED_BACK first)\nor any non-retryable stage fails
    RUNNING --> COMPLETED: RELEASE gate approved

    COMPLETED --> [*]
    FAILED --> [*]

    note right of RUNNING
        Fields populated as the run
        progresses (never overwritten):
        spec → design → implementation
        → testResult → guardrailResult
        → docsResult → releaseChecklist
        decisionLog only ever appends.
    end note
```

**Caption:** `WorkflowState` itself never resets — `RunStatus` cycles between `RUNNING` and a paused state (`PENDING_APPROVAL` or `BLOCKED`) until it reaches a terminal state. `Stage` (not shown here — see [02-Agent-Orchestration.md](02-Agent-Orchestration.md)) tracks *which* node runs next; `RunStatus` tracks *whether* the engine is currently allowed to run one.

---

## 4. Greenfield Execution Flow

```mermaid
flowchart TD
    Start(["Request: build-from-scratch feature"]) --> Req[RequirementsAgent]
    Req --> Gate1{{"Gate: REQUIREMENTS\nhuman approves"}}
    Gate1 --> Arch["ArchitectureAgent\n(GREENFIELD prompt —\nno existing codebase referenced)"]
    Arch --> Gate2{{"Gate: ARCHITECTURE\nhuman approves"}}
    Gate2 --> Plan["ChangePlanner + ClassNameEquivalence\n— even a 'from scratch' ask gets\nredirected to real existing classes\nwhen an equivalent already exists"]
    Plan --> Impl[ImplementationAgent writes to workspace]
    Impl --> Test["TestAgent — real ./gradlew test"]
    Test -- fail --> Impl
    Test -- pass --> Guard[GuardrailAgent]
    Guard --> Docs[DocsAgent]
    Docs --> Rel[ReleaseAgent]
    Rel --> Gate3{{"Gate: RELEASE\nhuman approves"}}
    Gate3 --> Done(["COMPLETED"])
```

**Caption:** evidenced by [Scenario 1](03-Workflow-Scenarios.md#scenario-1--greenfield) — the real run redirected 2 of 5 "new" files to existing equivalents rather than duplicating them, entirely via deterministic planning, before the LLM ever wrote a line of code.

---

## 5. Brownfield Execution Flow

```mermaid
flowchart TD
    Start(["Request: targeted change to existing feature"]) --> Req[RequirementsAgent]
    Req --> Gate1{{"Gate: REQUIREMENTS\nhuman approves"}}
    Gate1 --> Arch["ArchitectureAgent\n(BROWNFIELD prompt —\nreads real source tree via\nCodebaseContextService)"]
    Arch --> Gate2{{"Gate: ARCHITECTURE\nhuman approves"}}
    Gate2 --> Plan["ChangePlanner\n— prefers MODIFY over CREATE\nfor every plausible existing owner"]
    Plan --> TI["TestImpactAnalyzer\n— discovers existing tests referencing\nthe classes being modified"]
    TI --> Prompt["ImplementationPromptBuilder\n— production files + impacted test files,\nboth with full current content"]
    Prompt --> Impl["ImplementationAgent writes\nboth production AND test files"]
    Impl --> Test["TestAgent — real ./gradlew test"]
    Test -- fail --> Plan
    Test -- pass --> Guard[GuardrailAgent]
    Guard --> Docs[DocsAgent]
    Docs --> Rel[ReleaseAgent]
    Rel --> Gate3{{"Gate: RELEASE\nhuman approves"}}
    Gate3 --> Done(["COMPLETED"])
```

**Caption:** evidenced by [Scenario 2](03-Workflow-Scenarios.md#scenario-2--brownfield) — adding a field to `ShortenRequest` changes its constructor shape and breaks the pre-existing integration test's positional call; `TestImpactAnalyzer` is what lets that test get fixed in the *same* attempt as the production change, rather than failing blind at `TESTING`.

---

## 6. Ambiguous Execution Flow

```mermaid
flowchart TD
    Start(["Request: genuinely underspecified ask"]) --> Req["RequirementsAgent\n— returns clarificationQuestions,\nNOT a guessed assumption"]
    Req --> Gate1{{"Gate: REQUIREMENTS\nhuman approves WITH clarifying notes"}}
    Gate1 --> Arch1["ArchitectureAgent (1st pass)\n— clarificationQuestions non-empty,\nimpactedFiles empty → DEFERRED"]
    Arch1 --> Gate2a{{"Gate: ARCHITECTURE\nhuman approves, supplies\nthe clarifying answer as notes"}}
    Gate2a --> Check{"Engine: was design deferred\nAND are notes non-blank?"}
    Check -- yes --> Replan["Re-run ARCHITECTURE\nwith clarification appended\n(NOT just re-approving the same design)"]
    Replan --> Arch2["ArchitectureAgent (2nd pass)\n— real, scoped design produced"]
    Arch2 --> Gate2b{{"Gate: ARCHITECTURE (again)\nhuman approves the NEW design"}}
    Gate2b --> Impl[ImplementationAgent]
    Impl --> Test["TestAgent — real ./gradlew test"]
    Test -- fail --> Impl
    Test -- pass --> Guard[GuardrailAgent]
    Guard --> Docs[DocsAgent]
    Docs --> Rel[ReleaseAgent]
    Rel --> Gate3{{"Gate: RELEASE\nhuman approves"}}
    Gate3 --> Done(["COMPLETED"])
```

**Caption:** evidenced by [Scenario 3](03-Workflow-Scenarios.md#scenario-3--ambiguous) — this is the only path in the whole graph where a gate approval can loop *backward* (re-run `ARCHITECTURE`) instead of only ever advancing forward, and it requires **two** separate `ARCHITECTURE` approvals to reach `RELEASE`.

---

## 7. Workspace Isolation ("Patch, Don't Auto-Write")

```mermaid
flowchart LR
    subgraph Live["Live repository (never touched by the pipeline)"]
        LiveProduct["shortener-service/\n(real source + real tests)"]
    end

    subgraph RunA["run-artifacts/{runId-A}/workspace/"]
        CopyA["full repo copy A"]
    end

    subgraph RunB["run-artifacts/{runId-B}/workspace/"]
        CopyB["full repo copy B"]
    end

    LiveProduct -- "ensureWorkspace()\n(copied once, first use)" --> CopyA
    LiveProduct -- "ensureWorkspace()\n(copied once, first use)" --> CopyB

    ImplA[ImplementationAgent] -- writes --> CopyA
    TestA[TestAgent] -- "runs real ./gradlew test" --> CopyA
    GuardA[GuardrailAgent] -- scans --> CopyA

    ImplB[ImplementationAgent] -- writes --> CopyB
    TestB[TestAgent] -- "runs real ./gradlew test" --> CopyB

    CopyA -. "POST /runs/{A}/apply\n(only if status==COMPLETED)" .-> LiveProduct

    style LiveProduct fill:#f9d,stroke:#933,stroke-width:2px
```

**Caption:** two runs (even concurrent ones) never see each other's writes and never touch the real product. Only a `COMPLETED` run's files can ever cross the boundary back into `LiveProduct`, via one explicit, gated endpoint — a bad or half-finished run in `CopyB` has zero ability to affect `CopyA` or the live tree, no matter how many retries it takes.

---

## 8. Retry Mechanism (Two Independent Layers)

```mermaid
flowchart TD
    subgraph Outer["Workflow-level bounded retry — 'the plan was wrong'"]
        direction TB
        A1["IMPLEMENTATION executes"] --> A2["TESTING executes\n(real compileJava + test)"]
        A2 -- fail --> A3{"retriesFor(IMPLEMENTATION)\n>= MAX (2) ?"}
        A3 -- "no: increment, retry\nwith prior failure context" --> A1
        A3 -- "yes: stop" --> A4(["ROLLED_BACK → FAILED"])
        A2 -- pass --> A5(["continue to GUARDRAILS"])
    end

    subgraph Inner["LLM-client-level backoff — 'provider is throttling us'"]
        direction TB
        B1["complete(systemPrompt, userPrompt)"] --> B2{"HTTP 429 /\nrate_limit?"}
        B2 -- "no" --> B3(["return model response"])
        B2 -- "yes, attempts < 3" --> B4["sleep 2s, 4s (increasing)"]
        B4 --> B1
        B2 -- "yes, attempts == 3" --> B5(["throw — surfaces as a\ngenuine IMPLEMENTATION failure"])
    end

    A1 -.->|"every LLM call inside\nIMPLEMENTATION goes through"| B1
    B5 -.->|"only a PERSISTENT throttle\nreaches the outer layer"| A2

    style Outer fill:#eef,stroke:#448
    style Inner fill:#efe,stroke:#484
```

**Caption:** the two layers never share a counter. A transient `429` is absorbed invisibly inside a single `IMPLEMENTATION` attempt; only a genuinely bad plan (or a provider failure that outlasts the backoff) ever consumes one of the two workflow-level retry attempts.

---

## 9. Human Approval Gates

```mermaid
flowchart TD
    R[REQUIREMENTS] -- "execute() succeeds" --> G1{{"GATE\nstatus=PENDING_APPROVAL"}}
    G1 -- "POST /approve" --> A[ARCHITECTURE]

    A -- "execute() succeeds,\ndesign deferred + clarifying notes given" --> ReplanCheck{"dynamic re-plan\ncondition met?"}
    ReplanCheck -- yes --> A
    ReplanCheck -- no --> G2{{"GATE\nstatus=PENDING_APPROVAL"}}

    G2 -- "POST /approve" --> I[IMPLEMENTATION]
    I --> T[TESTING]
    T --> Gd[GUARDRAILS]

    Gd -- "violation found" --> B{{"BLOCKED\n(distinct from PENDING_APPROVAL)"}}
    B -- "POST /resolve-block" --> Doc1[DOCUMENTATION]
    Gd -- "no violation" --> Doc1

    Doc1 --> Rel[RELEASE]
    Rel -- "execute() succeeds\n(checklist may say not-ready,\nstill pauses)" --> G3{{"GATE\nstatus=PENDING_APPROVAL"}}
    G3 -- "POST /approve" --> Done(["COMPLETED"])

    style G1 fill:#ffd,stroke:#a80
    style G2 fill:#ffd,stroke:#a80
    style G3 fill:#ffd,stroke:#a80
    style B fill:#fdd,stroke:#a00
```

**Caption:** three gates (`REQUIREMENTS`, `ARCHITECTURE`, `RELEASE`) always pause for an explicit `approve` call; `BLOCKED` is a structurally distinct pause reachable only from `GUARDRAILS` and cleared only by `resolve-block` — the two mechanisms use different endpoints and different status values on purpose, so a blocked run can never be waved through by the wrong call.
