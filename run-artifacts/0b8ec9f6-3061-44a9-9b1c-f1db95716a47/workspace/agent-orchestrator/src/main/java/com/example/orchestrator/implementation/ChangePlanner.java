package com.example.orchestrator.implementation;

import com.example.orchestrator.domain.ArchitectureDesign;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * STEP 2 of the pipeline. Architecture's per-file CREATE/MODIFY guess is a
 * hint; this class is the actual authority. Three ways a file resolves:
 *  1. Exact path already exists in the index -> MODIFY at that path.
 *  2. No exact path, but an equivalent class exists under a different name
 *     (ClassNameEquivalence) -> MODIFY, redirected to the EXISTING file's
 *     real path. This is the direct fix for the reported bug: Architecture
 *     asking for "UrlShortenerService" when "ShortenerService" already
 *     exists no longer produces a duplicate CREATE.
 *  3. No match at all -> CREATE at the proposed path.
 * SKIP is reserved for the narrow, high-confidence case where the exact
 * capability already appears to be present (see isAlreadySatisfied).
 */
@Service
public class ChangePlanner {

    public List<ChangeDecision> plan(ProjectIndex index, ArchitectureDesign design) {
        List<ChangeDecision> decisions = new ArrayList<>();
        if (design == null || design.impactedFiles() == null) {
            return decisions;
        }

        for (ArchitectureDesign.ImpactedFile impacted : design.impactedFiles()) {
            decisions.add(planOne(index, impacted, design));
        }
        return decisions;
    }

    private ChangeDecision planOne(ProjectIndex index, ArchitectureDesign.ImpactedFile impacted, ArchitectureDesign design) {
        String proposedPath = impacted.path();

        Optional<ClassInfo> exactMatch = index.findByPath(proposedPath);
        if (exactMatch.isPresent()) {
            if (isAlreadySatisfied(exactMatch.get(), design)) {
                return new ChangeDecision(proposedPath, proposedPath, ChangeType.SKIP, exactMatch.get().simpleName(),
                        "File already exists and already exposes the required endpoint(s) - no change needed.");
            }
            return new ChangeDecision(proposedPath, proposedPath, ChangeType.MODIFY, exactMatch.get().simpleName(),
                    "Exact path already exists in the project.");
        }

        String proposedSimpleName = simpleNameFromPath(proposedPath);
        ClassKind guessedKind = ClassNameEquivalence.guessKindFromName(proposedSimpleName);
        Optional<ClassInfo> equivalent = ClassNameEquivalence.findEquivalent(proposedSimpleName, guessedKind, index);

        if (equivalent.isPresent()) {
            ClassInfo match = equivalent.get();
            if (isAlreadySatisfied(match, design)) {
                return new ChangeDecision(proposedPath, match.relativePath(), ChangeType.SKIP, match.simpleName(),
                        "Equivalent class '" + match.simpleName() + "' already exists and already satisfies this requirement.");
            }
            return new ChangeDecision(proposedPath, match.relativePath(), ChangeType.MODIFY, match.simpleName(),
                    "Architecture proposed '" + proposedSimpleName + "' but an equivalent class '"
                            + match.simpleName() + "' already exists at " + match.relativePath()
                            + " - redirecting to MODIFY that file instead of creating a duplicate.");
        }

        return new ChangeDecision(proposedPath, proposedPath, ChangeType.CREATE, null,
                "No existing or equivalent file found - genuinely new file.");
    }

    /** Conservative on purpose: only SKIP when we're confident the exact endpoint already exists. */
    private boolean isAlreadySatisfied(ClassInfo existing, ArchitectureDesign design) {
        if (design.apiEndpoints() == null || design.apiEndpoints().isEmpty() || existing.endpointMappings().isEmpty()) {
            return false;
        }
        return design.apiEndpoints().stream().allMatch(declared ->
                existing.endpointMappings().stream().anyMatch(declared::contains)
                        || existing.endpointMappings().stream().anyMatch(m -> declared.contains(m)));
    }

    private String simpleNameFromPath(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }
}
