package com.example.orchestrator.implementation;

import com.example.orchestrator.codebase.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Discovers existing test files impacted by a production-code change, so ImplementationAgent
 * can keep them compiling/passing instead of silently leaving them broken - without this, the
 * agent has zero way to know that e.g. adding a field to a record changes a constructor that an
 * existing integration test calls positionally.
 *
 * Two independent signals, unioned:
 *  - naming convention (Foo.java -> FooTest.java / FooTests.java / FooIT.java / FooIntegrationTest.java)
 *  - content reference (any test file whose source imports or otherwise references the changed
 *    class's simple name)
 *
 * Content reference is the authoritative signal - a project's tests don't have to follow a strict
 * 1:1 naming convention (this one doesn't: one integration test file exercises several classes at
 * once) - naming convention is a supplementary net for cases content-scanning might miss (e.g. a
 * test that references a class only via a mock without importing it under its own name elsewhere).
 */
@Service
public class TestImpactAnalyzer {

    private final WorkspaceService workspace;

    public TestImpactAnalyzer(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    /** Returns the relative paths (from the module root, e.g. "src/test/java/.../FooTest.java")
     * of existing test files impacted by changing the class named simpleClassName. */
    public List<String> findImpactedTests(String runId, String simpleClassName) {
        if (simpleClassName == null || simpleClassName.isBlank()) {
            return List.of();
        }
        List<String> testPaths = workspace.summarizeWorkspaceTestTree(runId).lines()
                .filter(line -> line.endsWith(".java"))
                .map(relative -> "src/test/java/" + relative)
                .toList();

        Pattern reference = Pattern.compile("\\b" + Pattern.quote(simpleClassName) + "\\b");
        Set<String> impacted = new LinkedHashSet<>();
        for (String testPath : testPaths) {
            if (isNamingMatch(testPath, simpleClassName)) {
                impacted.add(testPath);
                continue;
            }
            String content = workspace.readProductFile(runId, testPath);
            if (content != null && !content.isBlank() && reference.matcher(content).find()) {
                impacted.add(testPath);
            }
        }
        return List.copyOf(impacted);
    }

    private boolean isNamingMatch(String testPath, String simpleClassName) {
        String fileName = testPath.substring(testPath.lastIndexOf('/') + 1, testPath.length() - ".java".length());
        return fileName.equals(simpleClassName + "Test")
                || fileName.equals(simpleClassName + "Tests")
                || fileName.equals(simpleClassName + "IT")
                || fileName.equals(simpleClassName + "IntegrationTest");
    }
}
