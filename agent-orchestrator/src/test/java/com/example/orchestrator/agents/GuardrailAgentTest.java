package com.example.orchestrator.agents;

import com.example.orchestrator.codebase.WorkspaceService;
import com.example.orchestrator.domain.ImplementationResult;
import com.example.orchestrator.graph.NodeOutcome;
import com.example.orchestrator.graph.StageResult;
import com.example.orchestrator.state.ScenarioType;
import com.example.orchestrator.state.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailAgentTest {

    @TempDir
    Path tempRepoRoot;

    private WorkspaceService workspace;
    private GuardrailAgent guardrailAgent;

    @BeforeEach
    void setUp() {
        workspace = new WorkspaceService();
        ReflectionTestUtils.setField(workspace, "repoRoot", tempRepoRoot.toString());
        guardrailAgent = new GuardrailAgent(workspace);
    }

    @Test
    void blocksWhenAHardcodedAwsKeyIsPresent() {
        WorkflowState state = new WorkflowState("add config", ScenarioType.GREENFIELD);
        workspace.ensureWorkspace(state.getRunId());
        workspace.writeProductFile(state.getRunId(), "Config.java",
                "public class Config { String key = \"AKIAABCDEFGHIJKLMNOP\"; }");
        state.setImplementation(new ImplementationResult(List.of("Config.java"), "wrote config"));

        StageResult result = guardrailAgent.execute(state);

        assertThat(result.outcome()).isEqualTo(NodeOutcome.BLOCKED);
        assertThat(state.getGuardrailResult().passed()).isFalse();
        assertThat(state.getGuardrailResult().violations()).isNotEmpty();
    }

    @Test
    void passesWhenNoViolationsArePresent() {
        WorkflowState state = new WorkflowState("add config", ScenarioType.GREENFIELD);
        workspace.ensureWorkspace(state.getRunId());
        workspace.writeProductFile(state.getRunId(), "Clean.java",
                "public class Clean { String greeting = \"hello\"; }");
        state.setImplementation(new ImplementationResult(List.of("Clean.java"), "wrote clean file"));

        StageResult result = guardrailAgent.execute(state);

        assertThat(result.outcome()).isEqualTo(NodeOutcome.SUCCESS);
        assertThat(state.getGuardrailResult().passed()).isTrue();
        assertThat(state.getGuardrailResult().violations()).isEmpty();
    }
}
