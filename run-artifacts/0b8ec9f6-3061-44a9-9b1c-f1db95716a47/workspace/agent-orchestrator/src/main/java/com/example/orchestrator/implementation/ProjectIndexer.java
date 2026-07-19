package com.example.orchestrator.implementation;

import com.example.orchestrator.codebase.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * STEP 1 of the pipeline: scan every .java file under the workspace and
 * build a ProjectIndex. This runs once per ImplementationAgent.execute()
 * call, before any LLM call - everything downstream (ChangePlanner,
 * ImplementationPromptBuilder, ImplementationValidator) works off this
 * index rather than re-reading files.
 */
@Service
public class ProjectIndexer {

    private final WorkspaceService workspace;

    public ProjectIndexer(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    public ProjectIndex index(String runId) {
        List<String> relativePaths = workspace.summarizeWorkspaceSourceTree(runId).lines()
                .filter(line -> line.endsWith(".java"))
                .toList();

        List<ClassInfo> classes = new ArrayList<>();
        for (String relativePath : relativePaths) {
            String fullRelativePath = "src/main/java/" + relativePath;
            String source = workspace.readProductFile(runId, fullRelativePath);
            if (source == null || source.isBlank()) {
                continue;
            }
            classes.add(indexOne(fullRelativePath, source));
        }

        String combinedSource = workspace.concatenateAllJavaSource(runId);
        String persistenceApi = JavaSourceAnalysis.detectPersistenceApi(combinedSource);

        return new ProjectIndex(classes, persistenceApi);
    }

    private ClassInfo indexOne(String relativePath, String source) {
        String packageName = JavaSourceAnalysis.extractPackage(source);
        String simpleName = JavaSourceAnalysis.extractSimpleClassName(source);
        var annotations = JavaSourceAnalysis.extractClassLevelAnnotations(source);
        String superclass = JavaSourceAnalysis.extractSuperclass(source);
        var interfaces = JavaSourceAnalysis.extractInterfaces(source);
        ClassKind kind = JavaSourceAnalysis.inferKind(annotations, superclass, interfaces, simpleName);

        boolean isRepository = kind == ClassKind.REPOSITORY;
        boolean isInterface = JavaSourceAnalysis.isInterface(source);

        var publicMethods = JavaSourceAnalysis.extractPublicMethodNames(source);
        var repositoryMethods = (isRepository && isInterface)
                ? JavaSourceAnalysis.extractInterfaceMethodNames(source)
                : List.<String>of();
        var endpointMappings = JavaSourceAnalysis.extractEndpointMappings(source);

        return new ClassInfo(relativePath, packageName, simpleName, kind, annotations,
                superclass, interfaces, publicMethods, endpointMappings, repositoryMethods);
    }
}
