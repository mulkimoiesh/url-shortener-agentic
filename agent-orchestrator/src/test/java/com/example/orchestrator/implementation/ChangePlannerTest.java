package com.example.orchestrator.implementation;

import com.example.orchestrator.domain.ArchitectureDesign;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChangePlannerTest {

    private final ChangePlanner planner = new ChangePlanner();

    private ClassInfo existingController() {
        return new ClassInfo(
                "src/main/java/com/example/shortener/controller/ShortenController.java",
                "com.example.shortener.controller", "ShortenController", ClassKind.CONTROLLER,
                Set.of("RestController"), null, List.of(),
                List.of("shorten", "redirect"),
                List.of("POST /api/v1/shorten", "GET /{code}"),
                List.of());
    }

    @Test
    void redirectsProposedDuplicateToModifyTheRealExistingFile() {
        ProjectIndex index = new ProjectIndex(List.of(existingController()), "jakarta");
        ArchitectureDesign design = new ArchitectureDesign(
                List.of(new ArchitectureDesign.ImpactedFile(
                        "src/main/java/com/example/shortener/controller/UrlShortenerController.java",
                        "CREATE", "add analytics endpoint")),
                List.of("decision"), List.of("GET /analytics/{code}"), List.of(), "notes");

        List<ChangeDecision> decisions = planner.plan(index, design);

        assertThat(decisions).hasSize(1);
        ChangeDecision decision = decisions.get(0);
        assertThat(decision.changeType()).isEqualTo(ChangeType.MODIFY);
        assertThat(decision.resolvedPath()).isEqualTo(existingController().relativePath());
        assertThat(decision.matchedExistingClass()).isEqualTo("ShortenController");
    }

    @Test
    void exactPathMatchIsModify() {
        ProjectIndex index = new ProjectIndex(List.of(existingController()), "jakarta");
        ArchitectureDesign design = new ArchitectureDesign(
                List.of(new ArchitectureDesign.ImpactedFile(existingController().relativePath(), "CREATE", "wrong guess")),
                List.of(), List.of(), List.of(), "notes");

        List<ChangeDecision> decisions = planner.plan(index, design);

        assertThat(decisions.get(0).changeType()).isEqualTo(ChangeType.MODIFY);
    }

    @Test
    void genuinelyNewFileIsCreate() {
        ProjectIndex index = new ProjectIndex(List.of(existingController()), "jakarta");
        ArchitectureDesign design = new ArchitectureDesign(
                List.of(new ArchitectureDesign.ImpactedFile(
                        "src/main/java/com/example/shortener/service/QrCodeGenerator.java",
                        "CREATE", "genuinely new capability")),
                List.of(), List.of(), List.of(), "notes");

        List<ChangeDecision> decisions = planner.plan(index, design);

        assertThat(decisions.get(0).changeType()).isEqualTo(ChangeType.CREATE);
        assertThat(decisions.get(0).resolvedPath()).isEqualTo(decisions.get(0).architecturePath());
    }

    @Test
    void skipsWhenTheExactEndpointAlreadyExists() {
        ProjectIndex index = new ProjectIndex(List.of(existingController()), "jakarta");
        ArchitectureDesign design = new ArchitectureDesign(
                List.of(new ArchitectureDesign.ImpactedFile(existingController().relativePath(), "MODIFY", "add redirect")),
                List.of(), List.of("GET /{code}"), List.of(), "notes");

        List<ChangeDecision> decisions = planner.plan(index, design);

        assertThat(decisions.get(0).changeType()).isEqualTo(ChangeType.SKIP);
    }
}
