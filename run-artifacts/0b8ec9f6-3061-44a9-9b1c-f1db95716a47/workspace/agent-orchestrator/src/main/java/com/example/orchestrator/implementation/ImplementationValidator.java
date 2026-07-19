package com.example.orchestrator.implementation;

import com.example.orchestrator.codebase.WorkspaceService;
import com.example.orchestrator.domain.ImplementationPlan;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * STEP 7 of the pipeline: everything here runs BEFORE any file is written.
 * A single violation from any check fails the whole plan atomically - no
 * partial writes. Checks:
 *  - the LLM's returned action must match ChangePlanner's decision for that
 *    file (the LLM does not get to override CREATE/MODIFY)
 *  - duplicate class names within the plan itself
 *  - duplicate classes of the same role already in the project (via
 *    ClassNameEquivalence - catches the exact reported bug for any
 *    unplanned "extra" file the model adds)
 *  - duplicate REST endpoints (a new/changed mapping colliding with an
 *    existing one the plan isn't touching)
 *  - existing REST endpoints or public methods silently dropped on MODIFY
 *  - package declaration not matching its directory
 *  - a small set of common missing-import cases (full compiler is the
 *    backstop for anything subtler - see TestAgent)
 */
@Service
public class ImplementationValidator {

    private static final Map<String, String> REQUIRED_IMPORT_PREFIX_FOR_ANNOTATION = Map.of(
            "Entity", "persistence", "Id", "persistence", "GeneratedValue", "persistence", "Column", "persistence",
            "Service", "org.springframework.stereotype", "Repository", "org.springframework.stereotype",
            "RestController", "org.springframework.web.bind.annotation", "GetMapping", "org.springframework.web.bind.annotation",
            "PostMapping", "org.springframework.web.bind.annotation", "Autowired", "org.springframework.beans.factory.annotation"
    );

    private final WorkspaceService workspace;

    public ImplementationValidator(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    public List<String> validate(String runId, ProjectIndex index, List<ChangeDecision> decisions, ImplementationPlan plan) {
        List<String> violations = new ArrayList<>();
        Map<String, ChangeDecision> decisionByPath = decisions.stream()
                .collect(java.util.stream.Collectors.toMap(ChangeDecision::resolvedPath, d -> d, (a, b) -> a));

        Set<String> seenPaths = new HashSet<>();
        Set<String> pathsInPlan = new HashSet<>();
        for (ImplementationPlan.FileChange f : plan.files()) {
            pathsInPlan.add(f.path());
        }

        for (ImplementationPlan.FileChange f : plan.files()) {
            if (!seenPaths.add(f.path())) {
                violations.add(f.path() + ": appears more than once in the same plan");
                continue;
            }

            ChangeDecision decision = decisionByPath.get(f.path());
            if (decision != null) {
                // Planned file: our decision is authoritative, not the LLM's.
                violations.addAll(validatePlannedFile(runId, f, decision));
            } else {
                // Unplanned "extra" file the model added on its own - still validated, not trusted.
                violations.addAll(validateUnplannedFile(runId, index, f));
            }

            violations.addAll(checkPackageMatchesPath(f));
            String oldContent = (decision != null && decision.changeType() == ChangeType.MODIFY)
                    ? workspace.readProductFile(runId, decision.resolvedPath())
                    : null;
            violations.addAll(checkCommonMissingImports(f, oldContent));
        }

        violations.addAll(checkNoDuplicateEndpointsAcrossProject(runId, index, plan, pathsInPlan));

        return violations;
    }

    private List<String> validatePlannedFile(String runId, ImplementationPlan.FileChange f, ChangeDecision decision) {
        List<String> violations = new ArrayList<>();

        if (decision.changeType() == ChangeType.CREATE && workspace.productFileExists(runId, f.path())) {
            violations.add(f.path() + ": planned CREATE but file now exists (race or stale index) - should be MODIFY");
        }
        if (decision.changeType() == ChangeType.MODIFY) {
            String oldContent = workspace.readLiveProductFile(decision.resolvedPath());
            violations.addAll(checkNothingWasSilentlyRemoved(f.path(), oldContent, f.content()));
        }
        if (decision.changeType() == ChangeType.SKIP) {
            violations.add(f.path() + ": ChangePlanner marked this SKIP (already satisfied) but the model touched it anyway");
        }
        return violations;
    }

    private List<String> validateUnplannedFile(String runId, ProjectIndex index, ImplementationPlan.FileChange f) {
        List<String> violations = new ArrayList<>();

        if (workspace.productFileExists(runId, f.path())) {
            // The model touched an existing file that Architecture didn't flag as impacted
            // (e.g. a DTO field addition it missed) - this is unambiguously a MODIFY of that
            // exact file, not a naming collision with some other class, so validate it the
            // same way a planned MODIFY is validated instead of rejecting it outright.
            String oldContent = workspace.readLiveProductFile(f.path());
            violations.addAll(checkNothingWasSilentlyRemoved(f.path(), oldContent, f.content()));
            return violations;
        }

        String proposedName = JavaSourceAnalysis.extractSimpleClassName(f.content());
        if (proposedName == null) {
            return violations;
        }
        var annotations = JavaSourceAnalysis.extractClassLevelAnnotations(f.content());
        var superclass = JavaSourceAnalysis.extractSuperclass(f.content());
        var interfaces = JavaSourceAnalysis.extractInterfaces(f.content());
        ClassKind kind = JavaSourceAnalysis.inferKind(annotations, superclass, interfaces, proposedName);

        ClassNameEquivalence.findEquivalent(proposedName, kind, index).ifPresent(match ->
                violations.add(f.path() + ": adds unplanned class '" + proposedName
                        + "' which is equivalent to existing '" + match.simpleName() + "' at " + match.relativePath()
                        + " - reuse it instead"));

        return violations;
    }

    private List<String> checkNothingWasSilentlyRemoved(String path, String oldContent, String newContent) {
        List<String> violations = new ArrayList<>();

        Set<String> oldMappings = new HashSet<>(JavaSourceAnalysis.extractEndpointMappings(oldContent));
        Set<String> newMappings = new HashSet<>(JavaSourceAnalysis.extractEndpointMappings(newContent));
        Set<String> droppedMappings = new HashSet<>(oldMappings);
        droppedMappings.removeAll(newMappings);
        if (!droppedMappings.isEmpty()) {
            violations.add(path + ": removed existing REST mapping(s) " + droppedMappings);
        }

        Set<String> oldMethods = new HashSet<>(JavaSourceAnalysis.extractPublicMethodNames(oldContent));
        Set<String> newMethods = new HashSet<>(JavaSourceAnalysis.extractPublicMethodNames(newContent));
        oldMethods.addAll(JavaSourceAnalysis.extractInterfaceMethodNames(oldContent));
        newMethods.addAll(JavaSourceAnalysis.extractInterfaceMethodNames(newContent));
        Set<String> droppedMethods = new HashSet<>(oldMethods);
        droppedMethods.removeAll(newMethods);
        if (!droppedMethods.isEmpty()) {
            violations.add(path + ": would remove existing public/interface method(s) " + droppedMethods);
        }

        Set<String> oldTestMethods = new HashSet<>(JavaSourceAnalysis.extractTestMethodNames(oldContent));
        Set<String> newTestMethods = new HashSet<>(JavaSourceAnalysis.extractTestMethodNames(newContent));
        Set<String> droppedTestMethods = new HashSet<>(oldTestMethods);
        droppedTestMethods.removeAll(newTestMethods);
        if (!droppedTestMethods.isEmpty()) {
            violations.add(path + ": removed existing @Test method(s) " + droppedTestMethods
                    + " - fix call sites instead of deleting test coverage");
        }

        return violations;
    }

    private List<String> checkNoDuplicateEndpointsAcrossProject(String runId, ProjectIndex index,
                                                                  ImplementationPlan plan, Set<String> pathsInPlan) {
        List<String> violations = new ArrayList<>();
        Map<String, String> newMappingOwners = new HashMap<>();
        for (ImplementationPlan.FileChange f : plan.files()) {
            for (String mapping : JavaSourceAnalysis.extractEndpointMappings(f.content())) {
                newMappingOwners.merge(mapping, f.path(), (a, b) -> a + ", " + b);
                // collision with an existing class NOT part of this plan
                for (ClassInfo existing : index.classes()) {
                    if (pathsInPlan.contains(existing.relativePath())) {
                        continue; // that file is being modified as part of this same plan - fine
                    }
                    if (existing.endpointMappings().contains(mapping)) {
                        violations.add("Endpoint '" + mapping + "' in " + f.path()
                                + " duplicates an existing mapping already owned by " + existing.relativePath());
                    }
                }
            }
        }
        return violations;
    }

    private List<String> checkPackageMatchesPath(ImplementationPlan.FileChange f) {
        List<String> violations = new ArrayList<>();
        if (!f.path().startsWith("src/main/java/") || !f.path().endsWith(".java")) {
            return violations;
        }
        String declaredPackage = JavaSourceAnalysis.extractPackage(f.content());
        if (declaredPackage.isEmpty()) {
            return violations;
        }
        String expectedPackage = f.path()
                .substring("src/main/java/".length(), f.path().lastIndexOf('/'))
                .replace('/', '.');
        if (!declaredPackage.equals(expectedPackage)) {
            violations.add(f.path() + ": package '" + declaredPackage
                    + "' does not match its directory (expected '" + expectedPackage + "')");
        }
        return violations;
    }

    /**
     * oldContent is non-null only for MODIFY targets. An annotation the file already used
     * before this change is that file's existing (if unusual) style, not something this
     * change introduced - only flag annotations newly introduced by the plan's content.
     */
    private List<String> checkCommonMissingImports(ImplementationPlan.FileChange f, String oldContent) {
        List<String> violations = new ArrayList<>();
        String content = f.content();
        if (content == null) {
            return violations;
        }
        for (var entry : REQUIRED_IMPORT_PREFIX_FOR_ANNOTATION.entrySet()) {
            String annotation = entry.getKey();
            String requiredImportFragment = entry.getValue();
            boolean alreadyUsedBeforeThisChange = oldContent != null && oldContent.contains("@" + annotation);
            if (!alreadyUsedBeforeThisChange && content.contains("@" + annotation)
                    && !content.contains("import " + requiredImportFragment)
                    && !content.contains("import jakarta." + requiredImportFragment)
                    && !content.contains("import javax." + requiredImportFragment)) {
                violations.add(f.path() + ": uses @" + annotation + " but no matching import containing '"
                        + requiredImportFragment + "' was found (may be a compile error - full compiler will confirm)");
            }
        }
        return violations;
    }
}
