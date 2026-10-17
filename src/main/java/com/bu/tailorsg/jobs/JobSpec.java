package com.bu.tailorsg.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What a job posting asks for (spec 2.3), extracted by the model and correctable by the user.
 * Lists are never null.
 *
 * @param companyFacts only facts stated in the posting (used by the cover letter and CL-04)
 * @param warnings     extraction warnings, e.g. a screenshot that looked cut off
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobSpec(
        String title,
        String company,
        String location,
        String seniority,
        String employmentType,
        SalaryRange salaryRange,
        List<JdSkill> requiredSkills,
        List<JdSkill> preferredSkills,
        List<String> responsibilities,
        List<String> qualifications,
        List<String> keywordsRaw,
        List<String> companyFacts,
        String applyInstructions,
        Boolean coverLetterRequested,
        List<String> warnings) {

    /** {@code Boolean}, not {@code boolean}: Jackson 3 rejects a missing primitive, and a partial PUT may omit it. */
    public JobSpec {
        coverLetterRequested = Boolean.TRUE.equals(coverLetterRequested);
        requiredSkills = orEmpty(requiredSkills);
        preferredSkills = orEmpty(preferredSkills);
        responsibilities = orEmpty(responsibilities);
        qualifications = orEmpty(qualifications);
        keywordsRaw = orEmpty(keywordsRaw);
        companyFacts = orEmpty(companyFacts);
        warnings = orEmpty(warnings);
    }

    /** A skill as the posting names it, with common aliases ("Postgres" for "PostgreSQL"). */
    public record JdSkill(String term, List<String> aliases) {
        public JdSkill {
            aliases = orEmpty(aliases);
        }

        public static JdSkill of(String term) {
            return new JdSkill(term, List.of());
        }
    }

    public record SalaryRange(Double min, Double max, String currency) {}

    /** A minimal spec from hand-entered terms (Phase 3 keyword check before JD ingestion existed). */
    public static JobSpec ofTerms(String title, List<String> required, List<String> preferred) {
        return new JobSpec(title, null, null, null, null, null,
                required.stream().map(JdSkill::of).toList(), preferred.stream().map(JdSkill::of).toList(),
                List.of(), List.of(), List.of(), List.of(), null, false, List.of());
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
