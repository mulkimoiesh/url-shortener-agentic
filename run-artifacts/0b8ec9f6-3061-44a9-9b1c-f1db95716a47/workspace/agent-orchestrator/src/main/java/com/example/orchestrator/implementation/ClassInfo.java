package com.example.orchestrator.implementation;

import java.util.List;
import java.util.Set;

/**
 * Everything ChangePlanner, ImplementationValidator, and
 * ImplementationPromptBuilder need to know about one existing Java file,
 * without re-reading and re-parsing it repeatedly. Built once per
 * ImplementationAgent.execute() call by ProjectIndexer.
 */
public record ClassInfo(
        String relativePath,
        String packageName,
        String simpleName,
        ClassKind kind,
        Set<String> annotations,
        String superclass,
        List<String> interfaces,
        List<String> publicMethodNames,
        List<String> endpointMappings,   // "GET /path" style
        List<String> repositoryMethodNames
) {
}
