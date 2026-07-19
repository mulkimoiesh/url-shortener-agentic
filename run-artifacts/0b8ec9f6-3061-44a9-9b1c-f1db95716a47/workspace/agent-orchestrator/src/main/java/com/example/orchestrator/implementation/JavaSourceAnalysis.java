package com.example.orchestrator.implementation;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deliberately NOT a real Java parser - a small set of regexes good enough
 * to extract structural facts for indexing and validation. This is a known
 * trade-off (see FINAL_ENGINEERING_SUMMARY.md limitations): it can be
 * fooled by unusual formatting, but it's fast, dependency-free, and covers
 * the conventional Spring Boot style this project (and most brownfield
 * targets) actually uses. The real compiler (TestAgent's compileJava step)
 * is the ground-truth backstop for anything this misses.
 */
public final class JavaSourceAnalysis {

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern CLASS_OR_INTERFACE = Pattern.compile(
            "(?:public\\s+)?(?:final\\s+|abstract\\s+)?(class|interface|record)\\s+(\\w+)");
    private static final Pattern EXTENDS = Pattern.compile("(?:class|interface)\\s+\\w+\\s+extends\\s+([\\w<>.,\\s]+?)(?:\\{|implements)");
    private static final Pattern IMPLEMENTS = Pattern.compile("implements\\s+([\\w<>.,\\s]+?)\\{");
    private static final Pattern ANNOTATION = Pattern.compile("@(\\w+)");
    // Negative lookahead keeps a type/record/class/interface/enum HEADER (e.g. "public record
    // Foo(String a, ...) {") from being mistaken for a method named "Foo" - the non-greedy
    // return-type wildcard would otherwise swallow "record" as if it were a return type.
    private static final Pattern PUBLIC_METHOD = Pattern.compile(
            "public\\s+(?:static\\s+)?(?!(?:class|interface|enum|record)\\b)"
                    + "[\\w<>\\[\\],.\\s]+?\\s+(\\w+)\\s*\\([^)]*\\)\\s*[{;]");
    private static final Pattern INTERFACE_METHOD = Pattern.compile(
            "^\\s*[\\w<>\\[\\],.\\s]+?\\s+(\\w+)\\s*\\([^)]*\\)\\s*;", Pattern.MULTILINE);
    private static final Pattern MAPPING = Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)"
                    + "\\s*\\(\\s*(?:(?:value|path)\\s*=\\s*)?\"([^\"]*)\"");
    // JUnit test methods are typically package-private ("@Test void foo()"), not "public" - a
    // separate pattern from PUBLIC_METHOD is needed to catch a test being silently deleted rather
    // than merely having its call sites fixed up.
    private static final Pattern TEST_METHOD = Pattern.compile(
            "@Test\\b[^;{}]*?void\\s+(\\w+)\\s*\\([^)]*\\)");
    private static final Set<String> REPOSITORY_SUPERTYPES = Set.of(
            "JpaRepository", "CrudRepository", "PagingAndSortingRepository", "Repository");
    private static final Map<String, String> HTTP_METHOD_FOR_ANNOTATION = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH", "RequestMapping", "REQUEST");

    private JavaSourceAnalysis() {
    }

    public static String extractPackage(String source) {
        Matcher m = PACKAGE.matcher(nullToEmpty(source));
        return m.find() ? m.group(1) : "";
    }

    public static String extractSimpleClassName(String source) {
        Matcher m = CLASS_OR_INTERFACE.matcher(nullToEmpty(source));
        return m.find() ? m.group(2) : null;
    }

    public static boolean isInterface(String source) {
        Matcher m = CLASS_OR_INTERFACE.matcher(nullToEmpty(source));
        return m.find() && "interface".equals(m.group(1));
    }

    public static Set<String> extractClassLevelAnnotations(String source) {
        // annotations appearing before the class/interface declaration
        String s = nullToEmpty(source);
        Matcher classMatcher = CLASS_OR_INTERFACE.matcher(s);
        int classStart = classMatcher.find() ? classMatcher.start() : s.length();
        String header = s.substring(0, classStart);
        Set<String> result = new LinkedHashSet<>();
        Matcher m = ANNOTATION.matcher(header);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    public static String extractSuperclass(String source) {
        Matcher m = EXTENDS.matcher(nullToEmpty(source));
        if (!m.find()) {
            return null;
        }
        return firstTypeToken(m.group(1));
    }

    public static List<String> extractInterfaces(String source) {
        Matcher m = IMPLEMENTS.matcher(nullToEmpty(source));
        if (!m.find()) {
            return List.of();
        }
        return Arrays.stream(m.group(1).split(","))
                .map(JavaSourceAnalysis::firstTypeToken)
                .filter(Objects::nonNull)
                .toList();
    }

    public static List<String> extractPublicMethodNames(String source) {
        return matches(PUBLIC_METHOD, nullToEmpty(source));
    }

    /** @Test-annotated methods - used to make sure a "fix the test" edit doesn't silently delete one. */
    public static List<String> extractTestMethodNames(String source) {
        return matches(TEST_METHOD, nullToEmpty(source));
    }

    /** For repository interfaces, method declarations are implicitly public and have no body. */
    public static List<String> extractInterfaceMethodNames(String source) {
        return matches(INTERFACE_METHOD, nullToEmpty(source));
    }

    public static List<String> extractEndpointMappings(String source) {
        List<String> result = new ArrayList<>();
        Matcher m = MAPPING.matcher(nullToEmpty(source));
        while (m.find()) {
            String httpMethod = HTTP_METHOD_FOR_ANNOTATION.getOrDefault(m.group(1), m.group(1));
            result.add(httpMethod + " " + m.group(2));
        }
        return result;
    }

    public static boolean extendsSpringDataRepository(String superclass, List<String> interfaces) {
        String strippedSuperclass = stripGenerics(superclass);
        return (strippedSuperclass != null && REPOSITORY_SUPERTYPES.contains(strippedSuperclass))
                || interfaces.stream().map(JavaSourceAnalysis::stripGenerics)
                        .filter(Objects::nonNull).anyMatch(REPOSITORY_SUPERTYPES::contains);
    }

    public static ClassKind inferKind(Set<String> annotations, String superclass, List<String> interfaces, String simpleName) {
        if (annotations.contains("RestController") || annotations.contains("Controller")) {
            return ClassKind.CONTROLLER;
        }
        if (annotations.contains("Service")) {
            return ClassKind.SERVICE;
        }
        if (annotations.contains("Repository") || extendsSpringDataRepository(superclass, interfaces)) {
            return ClassKind.REPOSITORY;
        }
        if (annotations.contains("Entity")) {
            return ClassKind.ENTITY;
        }
        if (annotations.contains("Configuration")) {
            return ClassKind.CONFIGURATION;
        }
        if (simpleName != null && simpleName.endsWith("Exception")) {
            return ClassKind.EXCEPTION;
        }
        if (simpleName != null && (simpleName.endsWith("Dto") || simpleName.endsWith("DTO")
                || simpleName.endsWith("Request") || simpleName.endsWith("Response"))) {
            return ClassKind.DTO;
        }
        return ClassKind.OTHER;
    }

    /** Best-effort guess at which persistence API a project uses - avoids the javax/jakarta mixing failure mode. */
    public static String detectPersistenceApi(String combinedSource) {
        boolean jakarta = combinedSource.contains("jakarta.persistence");
        boolean javax = combinedSource.contains("javax.persistence");
        if (jakarta && !javax) return "jakarta";
        if (javax && !jakarta) return "javax";
        if (jakarta) return "jakarta";
        return "unknown";
    }

    private static List<String> matches(Pattern pattern, String content) {
        List<String> result = new ArrayList<>();
        Matcher m = pattern.matcher(content);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    private static String firstTypeToken(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String first = trimmed.split(",")[0].trim();
        return stripGenerics(first);
    }

    private static String stripGenerics(String type) {
        if (type == null) {
            return null;
        }
        int idx = type.indexOf('<');
        String base = idx == -1 ? type : type.substring(0, idx);
        int dot = base.lastIndexOf('.');
        return (dot == -1 ? base : base.substring(dot + 1)).trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
