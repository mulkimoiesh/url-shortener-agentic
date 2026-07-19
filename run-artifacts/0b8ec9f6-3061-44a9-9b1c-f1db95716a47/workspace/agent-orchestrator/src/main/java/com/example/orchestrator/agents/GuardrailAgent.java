package com.example.orchestrator.agents;

import com.example.orchestrator.codebase.WorkspaceService;
import com.example.orchestrator.domain.GuardrailResult;
import com.example.orchestrator.graph.NodeOutcome;
import com.example.orchestrator.graph.StageResult;
import com.example.orchestrator.graph.WorkflowNode;
import com.example.orchestrator.state.Actor;
import com.example.orchestrator.state.Stage;
import com.example.orchestrator.state.WorkflowState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real static analysis, read from this run's isolated workspace - not an
 * LLM opinion. A violation returns NodeOutcome.BLOCKED, which the engine
 * routes to a terminal BLOCKED state requiring an explicit human
 * resolve-block call.
 *
 * Two layers:
 *  1. Content-pattern rules on the files IMPLEMENTATION actually changed
 *     (secrets, keys, privacy).
 *  2. Whole-tree structural checks (duplicate @Entity/@Repository/@Service/
 *     @RestController class names, package-vs-directory mismatches) as a
 *     second line of defense beyond Implementation's own per-file diffing -
 *     catches cross-file duplication that a single-file diff can't see.
 */
@Service
public class GuardrailAgent implements WorkflowNode {

    private static final List<PolicyRule> CONTENT_RULES = List.of(
            new PolicyRule("hardcoded-secret",
                    Pattern.compile("(?i)(password|apiKey|api_key|secret)\\s*=\\s*\"[^\"]{4,}\"")),
            new PolicyRule("aws-access-key",
                    Pattern.compile("AKIA[0-9A-Z]{16}")),
            new PolicyRule("embedded-private-key",
                    Pattern.compile("-----BEGIN (RSA |EC )?PRIVATE KEY-----")),
            new PolicyRule("raw-ip-persistence",
                    Pattern.compile("getRemoteAddr\\s*\\(")) // policy: don't persist raw client IPs unhashed
    );

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern STEREOTYPE_CLASS_PATTERN = Pattern.compile(
            "@(Entity|Repository|Service|RestController|Controller)\\b[\\s\\S]{0,200}?public\\s+(?:final\\s+|abstract\\s+)?class\\s+(\\w+)");

    private final WorkspaceService workspace;

    public GuardrailAgent(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    @Override
    public Stage stage() {
        return Stage.GUARDRAILS;
    }

    @Override
    public StageResult execute(WorkflowState state) {
        String runId = state.getRunId();
        List<String> filesChanged = state.getImplementation() != null
                ? state.getImplementation().filesChanged()
                : List.of();

        List<String> violations = new ArrayList<>();
        violations.addAll(checkContentRules(runId, filesChanged));
        violations.addAll(checkDuplicateStereotypeClasses(runId));
        violations.addAll(checkPackageMatchesPath(runId, filesChanged));

        boolean passed = violations.isEmpty();
        GuardrailResult result = new GuardrailResult(passed, violations,
                passed ? "No policy violations found in " + filesChanged.size() + " changed file(s)."
                       : violations.size() + " violation(s) found - run blocked pending human review.");
        state.setGuardrailResult(result);
        state.addDecision(Stage.GUARDRAILS, Actor.AGENT, result.notes());

        return passed
                ? new StageResult(NodeOutcome.SUCCESS, result.notes())
                : new StageResult(NodeOutcome.BLOCKED, result.notes());
    }

    private List<String> checkContentRules(String runId, List<String> filesChanged) {
        List<String> violations = new ArrayList<>();
        for (String relativePath : filesChanged) {
            String content = workspace.readProductFile(runId, relativePath);
            for (PolicyRule rule : CONTENT_RULES) {
                if (rule.pattern().matcher(content).find()) {
                    violations.add(relativePath + ": violates policy '" + rule.name() + "'");
                }
            }
        }
        return violations;
    }

    /** Two files both declaring, e.g., @Entity class ShortUrl means something duplicated instead of reused. */
    private List<String> checkDuplicateStereotypeClasses(String runId) {
        List<String> violations = new ArrayList<>();
        String allSource = workspace.concatenateAllJavaSource(runId);
        Map<String, Integer> stereotypeClassCounts = new HashMap<>();

        Matcher m = STEREOTYPE_CLASS_PATTERN.matcher(allSource);
        while (m.find()) {
            String key = m.group(1) + ":" + m.group(2); // e.g. "Entity:ShortUrl"
            stereotypeClassCounts.merge(key, 1, Integer::sum);
        }
        stereotypeClassCounts.forEach((key, count) -> {
            if (count > 1) {
                violations.add("Duplicate @" + key.replace(":", " class ") + " declared " + count + " times in the workspace");
            }
        });
        return violations;
    }

    private List<String> checkPackageMatchesPath(String runId, List<String> filesChanged) {
        List<String> violations = new ArrayList<>();
        for (String relativePath : filesChanged) {
            if (!relativePath.startsWith("src/main/java/") || !relativePath.endsWith(".java")) {
                continue; // only check conventional source paths
            }
            String content = workspace.readProductFile(runId, relativePath);
            Matcher m = PACKAGE_PATTERN.matcher(content);
            if (!m.find()) {
                continue;
            }
            String declaredPackage = m.group(1);
            String expectedPackage = relativePath
                    .substring("src/main/java/".length(), relativePath.lastIndexOf('/'))
                    .replace('/', '.');
            if (!declaredPackage.equals(expectedPackage)) {
                violations.add(relativePath + ": package '" + declaredPackage
                        + "' does not match its directory (expected '" + expectedPackage + "')");
            }
        }
        return violations;
    }

    private record PolicyRule(String name, Pattern pattern) {
    }
}
