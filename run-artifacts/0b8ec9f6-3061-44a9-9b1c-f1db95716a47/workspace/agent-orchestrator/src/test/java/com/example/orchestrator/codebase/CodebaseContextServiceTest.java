package com.example.orchestrator.codebase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodebaseContextServiceTest {

    @TempDir
    Path tempRepoRoot;

    private CodebaseContextService codebase;

    @BeforeEach
    void setUp() {
        codebase = new CodebaseContextService();
        ReflectionTestUtils.setField(codebase, "repoRoot", tempRepoRoot.toString());
    }

    @Test
    void writesFileWithinTheGivenRoot() throws Exception {
        Path sandbox = tempRepoRoot.resolve("sandbox");
        codebase.writeFile(sandbox, "notes/hello.txt", "hi there");

        Path written = sandbox.resolve("notes/hello.txt");
        assertThat(Files.exists(written)).isTrue();
        assertThat(Files.readString(written)).isEqualTo("hi there");
    }

    @Test
    void refusesToWriteOutsideTheGivenRoot_viaPathTraversal() {
        Path sandbox = tempRepoRoot.resolve("sandbox");

        assertThrows(SecurityException.class,
                () -> codebase.writeFile(sandbox, "../../escaped.txt", "should never land here"));
    }

    @Test
    void summarizeSourceTree_reportsMissingDirectoryGracefully() {
        // no shortener-service directory exists under tempRepoRoot
        String summary = codebase.summarizeSourceTree();
        assertThat(summary).contains("not found");
    }
}
