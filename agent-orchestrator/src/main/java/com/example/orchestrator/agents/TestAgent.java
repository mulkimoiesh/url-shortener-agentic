package com.example.orchestrator.agents;

import com.example.orchestrator.codebase.WorkspaceService;
import com.example.orchestrator.domain.TestResult;
import com.example.orchestrator.graph.NodeOutcome;
import com.example.orchestrator.graph.StageResult;
import com.example.orchestrator.graph.WorkflowNode;
import com.example.orchestrator.state.Actor;
import com.example.orchestrator.state.Stage;
import com.example.orchestrator.state.WorkflowState;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs `./gradlew :shortener-service:test` for real, but inside this run's
 * isolated workspace (see WorkspaceService) - never against the live repo.
 * This means a broken generation fails safely: the live product is
 * untouched no matter what happens here.
 */
@Service
public class TestAgent implements WorkflowNode {

    private static final long TIMEOUT_SECONDS = 180;
    private static final int OUTPUT_TAIL_LINES = 40;

    private final WorkspaceService workspace;

    public TestAgent(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    @Override
    public Stage stage() {
        return Stage.TESTING;
    }

    @Override
    public StageResult execute(WorkflowState state) {
        String runId = state.getRunId();
        workspace.ensureWorkspace(runId); // defensive - should already exist from IMPLEMENTATION

        // Demo aid: lets the retry loop be exercised deterministically for the
        // assignment's scenario recordings, instead of hoping an LLM produces
        // broken code on cue. Clearly labeled in the audit log either way.
        if (isRetryDemoTrigger(state) && state.retriesFor(Stage.IMPLEMENTATION) < 2) {
            TestResult synthetic = new TestResult(false, 0, 1,
                    "[DEMO] Synthetic failure to exercise the bounded-retry loop deterministically. "
                            + "Real gradle test was not invoked on this attempt. "
                            + "Triggered by 'DEMO_RETRY_TRIGGER' in the raw requirement text.");
            state.setTestResult(synthetic);
            state.addDecision(Stage.TESTING, Actor.AGENT, synthetic.notes());
            return new StageResult(NodeOutcome.FAILURE, synthetic.notes());
        }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String wrapper = isWindows ? "gradlew.bat" : "./gradlew";

        // Compile first: faster failure signal, and lets us clearly label
        // "COMPILE FAILURE" vs "TEST FAILURE" in the notes fed back to
        // ImplementationAgent on retry.
        ProcessOutcome compileOutcome = runGradle(runId, wrapper, ":shortener-service:compileJava");
        if (compileOutcome.timedOut()) {
            return timeoutFailure(state);
        }
        if (compileOutcome.exitCode() != 0) {
            TestResult result = new TestResult(false, 0, 0,
                    "COMPILE FAILURE:\n" + tailLines(compileOutcome.output(), OUTPUT_TAIL_LINES));
            state.setTestResult(result);
            state.addDecision(Stage.TESTING, Actor.AGENT, "compileJava failed (exit code " + compileOutcome.exitCode() + ")");
            return new StageResult(NodeOutcome.FAILURE, "Compilation failed in workspace - see testResult.notes for compiler output.");
        }

        ProcessOutcome testOutcome = runGradle(runId, wrapper, ":shortener-service:test");
        if (testOutcome.timedOut()) {
            return timeoutFailure(state);
        }

        boolean passed = testOutcome.exitCode() == 0;
        String label = passed ? "" : "TEST FAILURE:\n";
        TestResult result = new TestResult(passed, -1, -1, label + tailLines(testOutcome.output(), OUTPUT_TAIL_LINES));
        state.setTestResult(result);
        state.addDecision(Stage.TESTING, Actor.AGENT,
                "gradlew test (workspace) exit code " + testOutcome.exitCode() + " (" + (passed ? "PASSED" : "FAILED") + ")");

        return passed
                ? new StageResult(NodeOutcome.SUCCESS, "Real test suite passed in workspace (exit code 0).")
                : new StageResult(NodeOutcome.FAILURE, "Real test suite failed in workspace (exit code " + testOutcome.exitCode() + "). See testResult.notes for output tail.");
    }

    private StageResult timeoutFailure(WorkflowState state) {
        TestResult timeoutResult = new TestResult(false, 0, 0,
                "Gradle run exceeded " + TIMEOUT_SECONDS + "s timeout and was killed.");
        state.setTestResult(timeoutResult);
        state.addDecision(Stage.TESTING, Actor.SYSTEM, timeoutResult.notes());
        return new StageResult(NodeOutcome.FAILURE, timeoutResult.notes());
    }

    private ProcessOutcome runGradle(String runId, String wrapper, String task) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        java.nio.file.Path workspaceRoot = workspace.workspaceRoot(runId);
        // Fully-qualify the wrapper path rather than relying on cmd.exe's current-directory
        // search - some Windows configurations (e.g. NoDefaultCurrentDirectoryInExePath) skip
        // searching the working directory for a bare filename, causing a false "not recognized"
        // even though the file genuinely exists there.
        String wrapperPath = workspaceRoot.resolve(wrapper).toAbsolutePath().toString();
        // CreateProcess (what ProcessBuilder uses on Windows) cannot launch a .bat/.cmd
        // file directly - it needs cmd.exe as the interpreter, or it fails with
        // "CreateProcess error=2" even though the file genuinely exists.
        List<String> command = isWindows
                ? List.of("cmd.exe", "/c", wrapperPath, task, "--console=plain")
                : List.of(wrapperPath, task, "--console=plain");
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workspaceRoot.toFile())
                .redirectErrorStream(true);
        try {
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ProcessOutcome.timeout();
            }
            return ProcessOutcome.of(process.exitValue(), output);
        } catch (IOException | InterruptedException e) {
            return ProcessOutcome.of(-1, "Failed to execute gradle: " + e.getMessage());
        }
    }

    private record ProcessOutcome(int exitCode, String output, boolean timedOut) {
        static ProcessOutcome of(int exitCode, String output) {
            return new ProcessOutcome(exitCode, output, false);
        }
        static ProcessOutcome timeout() {
            return new ProcessOutcome(-1, "", true);
        }
    }

    private boolean isRetryDemoTrigger(WorkflowState state) {
        return state.getRawRequirement() != null
                && state.getRawRequirement().toUpperCase().contains("DEMO_RETRY_TRIGGER");
    }

    private String tailLines(String output, int n) {
        List<String> lines = output.lines().toList();
        int from = Math.max(0, lines.size() - n);
        return String.join("\n", lines.subList(from, lines.size()));
    }
}
