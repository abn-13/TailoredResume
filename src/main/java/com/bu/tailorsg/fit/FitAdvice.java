package com.bu.tailorsg.fit;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/**
 * The writer model's ADVISORY job-fit narrative (prompts/fit-advice.md), after code has verified it against
 * the facts: project targets and gaps must be real gaps, strengths must cite master highlights and may not
 * name a gap. It never changes a check result (P4).
 */
public record FitAdvice(Recommendation recommendation, String headline, List<String> reasons, List<Strength> strengths,
                        List<GapPlan> gapsToAddress, ProjectSuggestion projectSuggestion) {

    public enum Recommendation {
        APPLY_NOW("Apply now"),
        APPLY_AND_ADDRESS_GAP("Apply, and address the gap"),
        BUILD_PROJECT_FIRST("Build a small project first"),
        SKIP("Probably skip this one");

        private final String label;

        Recommendation(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public FitAdvice {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        strengths = strengths == null ? List.of() : List.copyOf(strengths);
        gapsToAddress = gapsToAddress == null ? List.of() : List.copyOf(gapsToAddress);
    }

    public record Strength(String point, List<String> sourceIds) {
        public Strength {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        }
    }

    public record GapPlan(String term, String howToAddress) {}

    /** title "" means no project suggested. */
    public record ProjectSuggestion(String title, List<String> targetsGaps, String description, List<String> scope,
                                    int estimatedDays, String showcase) {
        public ProjectSuggestion {
            targetsGaps = targetsGaps == null ? List.of() : List.copyOf(targetsGaps);
            scope = scope == null ? List.of() : List.copyOf(scope);
        }

        @JsonIgnore
        public boolean isPresent() {
            return title != null && !title.isBlank();
        }
    }

    /**
     * What is stored on the application: the verified advice, what verification changed, and provenance.
     *
     * @param notes what code removed or flagged in the model's answer
     */
    public record Stored(FitAdvice advice, List<String> notes, String model, int promptVersion, Integer masterVersionNo,
                         Instant createdAt) {}
}
