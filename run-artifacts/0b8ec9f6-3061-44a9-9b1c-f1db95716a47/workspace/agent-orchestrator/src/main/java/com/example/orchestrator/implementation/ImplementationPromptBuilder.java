package com.example.orchestrator.implementation;

import com.example.orchestrator.codebase.WorkspaceService;
import com.example.orchestrator.domain.ArchitectureDesign;
import com.example.orchestrator.state.WorkflowState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ImplementationPromptBuilder {

    /**
     * Deliberately short and generic: correctness is enforced by
     * ChangePlanner (before this call) and ImplementationValidator (after
     * it), not by asking the model to "silently self-review" a long
     * checklist. The model's only job is to generate code for the exact
     * files it's told about.
     */
    public static final String SYSTEM_PROMPT = """
            You are a Java/Spring Boot code generator operating inside an
            automated pipeline. You will be told exactly which files to
            CREATE and which to MODIFY - these decisions were made by
            analyzing the real project structure and are final; do not
            second-guess them or invent additional files unless genuinely
            necessary (e.g. a small new DTO).

            For every file listed under "Files to MODIFY", you are given its
            full current content. Return the FULL file with your change
            applied, preserving every existing method, field, import, and
            REST endpoint mapping not directly related to this change.

            For every file listed under "Files to CREATE", write it from
            scratch, matching the existing project's package/style shown in
            "Existing classes" below.

            If any files are listed under "Existing TEST files impacted by
            the production changes above", those tests reference code you
            are changing and may no longer compile or pass (e.g. a
            constructor call whose signature you changed). Fix ONLY what is
            necessary to keep each one compiling and passing - preserve its
            existing test intent, assertions, and coverage. Do not delete or
            weaken a test to make it pass, and do not touch any test file
            not listed there.

            Respond with ONLY valid JSON, no markdown fences, no commentary:
            {
              "files": [{"path": "...", "content": "full file content", "action": "CREATE or MODIFY"}],
              "notes": "1-3 sentences"
            }
            """;

    private final WorkspaceService workspace;

    public ImplementationPromptBuilder(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    public String buildUserPrompt(String runId, ProjectIndex index, ArchitectureDesign design,
                                   List<ChangeDecision> decisions, List<ChangeDecision> impactedTestDecisions,
                                   List<String> acceptanceCriteria,
                                   String priorFailureContext, String rawRequirement) {
        List<ChangeDecision> toModify = decisions.stream().filter(d -> d.changeType() == ChangeType.MODIFY).toList();
        List<ChangeDecision> toCreate = decisions.stream().filter(d -> d.changeType() == ChangeType.CREATE).toList();

        List<ChangeDecision> allModifyTargets = Stream.concat(toModify.stream(), impactedTestDecisions.stream()).toList();

        return """
                SCHEMA_ID: IMPLEMENTATION_V1
                Original raw requirement: %s
                Project summary: %d existing classes, persistence API = %s

                Existing classes (path and role only - not full content):
                %s

                Files to MODIFY (decided by static analysis - use these exact paths):
                %s

                Files to CREATE (decided by static analysis - no equivalent existing class found):
                %s

                Existing TEST files impacted by the production changes above (see system
                prompt for how to handle these - fix only what's necessary, bounded to
                just the tests actually referencing the changed code):
                %s

                Architecture decisions: %s
                Required API endpoints: %s
                Acceptance criteria: %s
                %s
                EXISTING FILE CONTENTS for MODIFY targets (production and test):
                %s
                """.formatted(
                rawRequirement,
                index.classes().size(),
                index.persistenceApi(),
                index.summarize(),
                describeDecisions(toModify),
                describeDecisions(toCreate),
                describeDecisions(impactedTestDecisions),
                design.designDecisions(),
                design.apiEndpoints(),
                acceptanceCriteria,
                priorFailureContext == null ? "" : priorFailureContext,
                existingContentBlock(runId, allModifyTargets));
    }

    private String describeDecisions(List<ChangeDecision> decisions) {
        if (decisions.isEmpty()) {
            return "(none)";
        }
        return decisions.stream()
                .map(d -> "  - " + d.resolvedPath() + ": " + d.reason())
                .collect(Collectors.joining("\n"));
    }

    private String existingContentBlock(String runId, List<ChangeDecision> toModify) {
        if (toModify.isEmpty()) {
            return "(no MODIFY targets)";
        }
        StringBuilder sb = new StringBuilder();
        for (ChangeDecision d : toModify) {
            String content = workspace.readProductFile(runId, d.resolvedPath());
            sb.append("--- FILE: ").append(d.resolvedPath()).append(" ---\n")
                    .append(content).append("\n--- END FILE ---\n\n");
        }
        return sb.toString();
    }
}
