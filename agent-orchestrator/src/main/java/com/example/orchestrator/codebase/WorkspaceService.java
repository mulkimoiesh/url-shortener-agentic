package com.example.orchestrator.codebase;

import jakarta.annotation.PostConstruct;
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
 * Answers the direct-write-vs-patch design question: this service is the
 * "patch" side. Every run gets its own isolated copy of the whole repo under
 * run-artifacts/{runId}/workspace/. Implementation writes here, Testing runs
 * the real Gradle suite here, Guardrails scans here - the live shortener-service
 * module is never touched by the automated pipeline. Only applyToLiveProduct()
 * mutates the real product, and it is only ever called from the controller
 * after a run has reached COMPLETED (i.e. the RELEASE gate was approved).
 */
@Service
public class WorkspaceService {

    private static final List<String> EXCLUDED_DIR_NAMES =
            List.of("build", ".gradle", "run-artifacts", ".git", ".idea", "target");

    @Value("${app.codebase.repo-root}")
    private String repoRoot;

    public Path repoRoot() {
        return Path.of(repoRoot).toAbsolutePath().normalize();
    }

    /**
     * app.codebase.repo-root is a path relative to the PROCESS'S working directory, which
     * `./gradlew :agent-orchestrator:bootRun` always sets correctly (to the agent-orchestrator/
     * module dir) but a raw `java -jar` invocation or an IDE run configuration may not - in which
     * case every run-artifacts path silently resolves to the wrong place and the failure doesn't
     * surface until deep inside a workflow run (a confusing "gradlew.bat is not recognized" error
     * many minutes later). Fail fast at startup instead, with an actionable message.
     */
    @PostConstruct
    void validateRepoRoot() {
        Path resolvedRoot = repoRoot();
        Path expectedMarker = resolvedRoot.resolve("shortener-service").resolve("build.gradle");
        if (!Files.exists(expectedMarker)) {
            throw new IllegalStateException(
                    "app.codebase.repo-root ('" + repoRoot + "') resolved to " + resolvedRoot
                            + ", but no shortener-service/build.gradle was found there. This usually means "
                            + "the process's working directory isn't what repo-root assumes - e.g. the app was "
                            + "started via `java -jar` or an IDE run configuration instead of "
                            + "`./gradlew :agent-orchestrator:bootRun` (which sets the working directory "
                            + "correctly). Either run via that Gradle task, or set app.codebase.repo-root to an "
                            + "absolute path pointing at the repo root.");
        }
    }

    public Path workspaceRoot(String runId) {
        return repoRoot().resolve("run-artifacts").resolve(runId).resolve("workspace");
    }

    public Path workspaceProductRoot(String runId) {
        return workspaceRoot(runId).resolve("shortener-service");
    }

    /** Idempotent: copies the live repo into this run's workspace exactly once (safe to call on every retry). */
    public synchronized void ensureWorkspace(String runId) {
        Path workspace = workspaceRoot(runId);
        if (Files.exists(workspace)) {
            return;
        }
        try {
            copyRecursively(repoRoot(), workspace);
            makeExecutable(workspace.resolve("gradlew"));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to provision workspace for run " + runId, e);
        }
    }

    public boolean productFileExists(String runId, String relativePath) {
        return Files.exists(resolveWithinWorkspaceProduct(runId, relativePath));
    }

    public String readProductFile(String runId, String relativePath) {
        Path target = resolveWithinWorkspaceProduct(runId, relativePath);
        try {
            return Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads a file from the untouched LIVE product, bypassing this run's workspace copy
     * entirely. Used as the baseline for "did this change silently drop something" checks -
     * the workspace copy gets overwritten by every IMPLEMENTATION retry attempt, so diffing
     * against it would compare a new attempt against a previous, possibly-broken attempt
     * instead of against the real starting point.
     */
    public String readLiveProductFile(String relativePath) {
        Path liveProduct = repoRoot().resolve("shortener-service").normalize().toAbsolutePath();
        Path target = liveProduct.resolve(relativePath).normalize().toAbsolutePath();
        if (!target.startsWith(liveProduct)) {
            throw new SecurityException("Refusing to read outside live product root: " + relativePath);
        }
        try {
            return Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeProductFile(String runId, String relativePath, String content) {
        Path target = resolveWithinWorkspaceProduct(runId, relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String summarizeWorkspaceSourceTree(String runId) {
        Path srcRoot = workspaceProductRoot(runId).resolve("src/main/java");
        if (!Files.isDirectory(srcRoot)) {
            return "(shortener-service source tree not found in workspace at " + srcRoot + ")";
        }
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .map(srcRoot::relativize)
                    .map(p -> p.toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Same as {@link #summarizeWorkspaceSourceTree}, but for src/test/java - used to discover existing
     * test files impacted by a production change so ImplementationAgent can keep them passing. */
    public String summarizeWorkspaceTestTree(String runId) {
        Path testRoot = workspaceProductRoot(runId).resolve("src/test/java");
        if (!Files.isDirectory(testRoot)) {
            return "";
        }
        try (Stream<Path> walk = Files.walk(testRoot)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .map(testRoot::relativize)
                    .map(p -> p.toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Concatenates every .java source file in the workspace - used for functional-completeness scanning. */
    public String concatenateAllJavaSource(String runId) {
        Path srcRoot = workspaceProductRoot(runId).resolve("src/main/java");
        if (!Files.isDirectory(srcRoot)) {
            return "";
        }
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The one explicit, human-triggered action that mutates the real product.
     * Only called by the controller for runs whose status is COMPLETED
     * (i.e. every gate, including RELEASE, was approved by a human).
     */
    public List<String> applyToLiveProduct(String runId, List<String> relativeFilePaths) {
        Path workspaceProduct = workspaceProductRoot(runId);
        Path liveProduct = repoRoot().resolve("shortener-service").normalize().toAbsolutePath();

        return relativeFilePaths.stream()
                .peek(relativePath -> {
                    Path source = workspaceProduct.resolve(relativePath).normalize();
                    Path target = liveProduct.resolve(relativePath).normalize().toAbsolutePath();
                    if (!target.startsWith(liveProduct)) {
                        throw new SecurityException("Refusing to apply file outside live product root: " + relativePath);
                    }
                    try {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to apply " + relativePath + " to live product", e);
                    }
                })
                .toList();
    }

    private Path resolveWithinWorkspaceProduct(String runId, String relativePath) {
        Path root = workspaceProductRoot(runId).normalize().toAbsolutePath();
        Path resolved = root.resolve(relativePath).normalize().toAbsolutePath();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Refusing to access outside workspace product root: " + relativePath);
        }
        return resolved;
    }

    private void copyRecursively(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path p : (Iterable<Path>) walk.filter(p -> !isExcluded(source, p))::iterator) {
                Path rel = source.relativize(p);
                Path dest = target.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private boolean isExcluded(Path source, Path candidate) {
        Path rel = source.relativize(candidate);
        for (Path part : rel) {
            if (EXCLUDED_DIR_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private void makeExecutable(Path file) {
        if (Files.exists(file)) {
            file.toFile().setExecutable(true);
        }
    }
}
