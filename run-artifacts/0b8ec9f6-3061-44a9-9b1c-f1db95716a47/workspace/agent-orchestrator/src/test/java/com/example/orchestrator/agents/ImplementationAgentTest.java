package com.example.orchestrator.agents;

import com.example.orchestrator.codebase.WorkspaceService;
import com.example.orchestrator.domain.ArchitectureDesign;
import com.example.orchestrator.domain.RequirementSpec;
import com.example.orchestrator.graph.NodeOutcome;
import com.example.orchestrator.graph.StageResult;
import com.example.orchestrator.implementation.ChangePlanner;
import com.example.orchestrator.implementation.ImplementationPromptBuilder;
import com.example.orchestrator.implementation.ImplementationValidator;
import com.example.orchestrator.implementation.ProjectIndexer;
import com.example.orchestrator.implementation.TestImpactAnalyzer;
import com.example.orchestrator.llm.AgentLlmClient;
import com.example.orchestrator.state.ScenarioType;
import com.example.orchestrator.state.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImplementationAgentTest {

    @TempDir
    Path tempRepoRoot;

    private WorkspaceService workspace;
    private ObjectMapper objectMapper;
    private ImplementationAgent agent;

    private static final String EXISTING_PATH = "src/main/java/com/example/shortener/controller/ShortenController.java";
    private static final String EXISTING_CONTENT = """
            package com.example.shortener.controller;
            @RestController
            public class ShortenController {
                @PostMapping("/api/v1/shorten")
                public ResponseEntity<ShortenResponse> shorten(ShortenRequest request) { return null; }
                @GetMapping("/{code}")
                public ResponseEntity<Void> redirect(String code) { return null; }
            }
            """;

    @BeforeEach
    void setUp() {
        workspace = new WorkspaceService();
        ReflectionTestUtils.setField(workspace, "repoRoot", tempRepoRoot.toString());
        objectMapper = new ObjectMapper();
    }

    private void buildAgentWithResponse(String llmResponse) {
        AgentLlmClient fixedClient = (systemPrompt, userPrompt) -> llmResponse;
        agent = new ImplementationAgent(fixedClient, objectMapper, workspace,
                new ProjectIndexer(workspace), new ChangePlanner(),
                new ImplementationPromptBuilder(workspace), new ImplementationValidator(workspace),
                new TestImpactAnalyzer(workspace));
    }

    private WorkflowState stateWithExistingFileAsModifyTarget(String architecturePath, String changeType) {
        WorkflowState state = new WorkflowState("add a feature", ScenarioType.BROWNFIELD);
        state.setSpec(new RequirementSpec(
                List.of("functional req"), List.of("non-functional req"),
                List.of("criteria"), List.of(), List.of(), List.of()));
        state.setDesign(new ArchitectureDesign(
                List.of(new ArchitectureDesign.ImpactedFile(architecturePath, changeType, "add a field")),
                List.of("decision"), List.of(), List.of(), "notes"));

        // Seed the LIVE product root (not just the run's workspace copy) - the validator's
        // "silently removed" check now diffs against the live root, since the workspace copy
        // gets overwritten by every retry attempt and is no longer a trustworthy baseline.
        writeLiveProductFile(EXISTING_PATH, EXISTING_CONTENT);
        workspace.ensureWorkspace(state.getRunId());
        return state;
    }

    private void writeLiveProductFile(String relativePath, String content) {
        try {
            Path target = tempRepoRoot.resolve("shortener-service").resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void rejectsAPlanThatSilentlyDropsAnExistingEndpoint() {
        String brokenPlanJson = """
                {"files":[{"path":"%s","content":"package com.example.shortener.controller;\\npublic class ShortenController { @PostMapping(\\"/api/v1/shorten\\") public ResponseEntity<ShortenResponse> shorten(ShortenRequest r) { return null; } }","action":"MODIFY"}],"notes":"regenerated"}
                """.formatted(EXISTING_PATH);
        buildAgentWithResponse(brokenPlanJson);
        WorkflowState state = stateWithExistingFileAsModifyTarget(EXISTING_PATH, "MODIFY");

        StageResult result = agent.execute(state);

        assertThat(result.outcome()).isEqualTo(NodeOutcome.FAILURE);
        assertThat(result.summary()).contains("removed existing REST mapping");
        assertThat(workspace.readProductFile(state.getRunId(), EXISTING_PATH)).isEqualTo(EXISTING_CONTENT);
    }

    @Test
    void acceptsAPlanThatPreservesExistingEndpointsWhileAddingANewOne() {
        String goodPlanJson = """
                {"files":[{"path":"%s","content":"package com.example.shortener.controller;\\npublic class ShortenController { @PostMapping(\\"/api/v1/shorten\\") public ResponseEntity<ShortenResponse> shorten(ShortenRequest r) { return null; } @GetMapping(\\"/{code}\\") public ResponseEntity<Void> redirect(String c) { return null; } @GetMapping(\\"/api/v1/{code}/stats\\") public StatsResponse stats(String c) { return null; } }","action":"MODIFY"}],"notes":"added stats endpoint, kept everything else"}
                """.formatted(EXISTING_PATH);
        buildAgentWithResponse(goodPlanJson);
        WorkflowState state = stateWithExistingFileAsModifyTarget(EXISTING_PATH, "MODIFY");

        StageResult result = agent.execute(state);

        assertThat(result.outcome()).isEqualTo(NodeOutcome.SUCCESS);
        assertThat(state.getImplementation().filesChanged()).containsExactly(EXISTING_PATH);
        assertThat(workspace.readProductFile(state.getRunId(), EXISTING_PATH)).contains("stats");
    }

    @Test
    void theExactReportedBug_architectureAsksForDuplicateButPlannerRedirectsToModify() {
        // Architecture proposes a brand-new "UrlShortenerController" even though
        // ShortenController already exists - ChangePlanner must redirect this to
        // MODIFY of the real file, and the model's response targets that resolved path.
        String planJson = """
                {"files":[{"path":"%s","content":"package com.example.shortener.controller;\\npublic class ShortenController { @PostMapping(\\"/api/v1/shorten\\") public ResponseEntity<ShortenResponse> shorten(ShortenRequest r) { return null; } @GetMapping(\\"/{code}\\") public ResponseEntity<Void> redirect(String c) { return null; } }","action":"MODIFY"}],"notes":"reused existing controller instead of creating a duplicate"}
                """.formatted(EXISTING_PATH);
        buildAgentWithResponse(planJson);
        WorkflowState state = stateWithExistingFileAsModifyTarget(
                "src/main/java/com/example/shortener/controller/UrlShortenerController.java", "CREATE");

        StageResult result = agent.execute(state);

        assertThat(result.outcome()).isEqualTo(NodeOutcome.SUCCESS);
        // No new "UrlShortenerController.java" file should exist anywhere.
        assertThat(workspace.productFileExists(state.getRunId(),
                "src/main/java/com/example/shortener/controller/UrlShortenerController.java")).isFalse();
    }

    @Test
    void emptyFileListIsTreatedAsSuccessNotAFailure() {
        buildAgentWithResponse("{\"files\":[],\"notes\":\"Feature already exists - no changes needed.\"}");
        WorkflowState state = stateWithExistingFileAsModifyTarget(EXISTING_PATH, "MODIFY");

        StageResult result = agent.execute(state);

        assertThat(result.outcome()).isEqualTo(NodeOutcome.SUCCESS);
        assertThat(state.getImplementation().filesChanged()).isEmpty();
    }
}
