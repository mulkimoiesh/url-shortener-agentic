package com.example.orchestrator.codebase;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Real filesystem access into the product module, scoped and defensive:
 *  - summarize(): lists .java files under shortener-service so the
 *    Architecture Agent can reason about impacted components instead of
 *    guessing (this is the "Codebase Reasoning (Brownfield)" requirement).
 *  - readFile()/writeFile(): used by the Implementation/Guardrail agents.
 *    writeFile() refuses to write outside the target module root - agents
 *    cannot be tricked (by a bad LLM response) into writing arbitrary paths
 *    on the host.
 */
@Service
public class CodebaseContextService {

    @Value("${app.codebase.repo-root}")
    private String repoRoot;

    public Path productRoot() {
        return Path.of(repoRoot, "shortener-service").toAbsolutePath().normalize();
    }

    public Path repoRoot() {
        return Path.of(repoRoot).toAbsolutePath().normalize();
    }

    public String summarizeSourceTree() {
        Path srcRoot = productRoot().resolve("src/main/java");
        if (!Files.isDirectory(srcRoot)) {
            return "(shortener-service source tree not found at " + srcRoot + ")";
        }
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .map(srcRoot::relativize)
                    .map(Path::toString)
                    .sorted()
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String readFile(String relativePathFromProductRoot) {
        Path target = resolveWithinProduct(relativePathFromProductRoot);
        try {
            if (!Files.exists(target)) {
                return "";
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes under the given root (product root for real runs, a sandbox dir for mock runs). */
    public void writeFile(Path root, String relativePath, String content) {
        Path target = resolveWithin(root, relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path sandboxRoot(String runId) {
        return repoRoot().resolve("run-artifacts").resolve(runId).resolve("mock-generated");
    }

    public Path runArtifactsRoot(String runId) {
        return repoRoot().resolve("run-artifacts").resolve(runId);
    }

    private Path resolveWithinProduct(String relativePath) {
        return resolveWithin(productRoot(), relativePath);
    }

    private Path resolveWithin(Path root, String relativePath) {
        Path normalizedRoot = root.normalize().toAbsolutePath();
        Path resolved = normalizedRoot.resolve(relativePath).normalize().toAbsolutePath();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new SecurityException("Refusing to write outside of " + normalizedRoot + " (got: " + relativePath + ")");
        }
        return resolved;
    }
}
