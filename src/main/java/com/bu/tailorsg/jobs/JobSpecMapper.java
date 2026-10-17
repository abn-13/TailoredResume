package com.bu.tailorsg.jobs;

import com.bu.tailorsg.jobs.JobSpec.JdSkill;
import com.bu.tailorsg.jobs.JobSpec.SalaryRange;
import com.bu.tailorsg.jobs.source.FetchedJob;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Model output ({@code schema/llm/job-spec.schema.json}, everything required) to {@link JobSpec}: "" and 0
 * become absent values, duplicate skills are merged, and structured fields from the source site (title,
 * company, salary) override the model's reading of the text.
 */
public final class JobSpecMapper {

    private JobSpecMapper() {}

    public static JobSpec map(JsonNode llm, FetchedJob source) {
        JsonNode salary = llm.path("salaryRange");
        double min = salary.path("min").asDouble(0);
        double max = salary.path("max").asDouble(0);
        SalaryRange range = min <= 0 && max <= 0 ? null
                : new SalaryRange(min > 0 ? min : null, max > 0 ? max : null, blankToNull(salary.path("currency").stringValue("")));
        List<JdSkill> required = skills(llm.path("requiredSkills"), Set.of());
        Set<String> requiredTerms = new LinkedHashSet<>();
        required.forEach(s -> requiredTerms.add(s.term().toLowerCase(Locale.ROOT)));
        List<JdSkill> preferred = skills(llm.path("preferredSkills"), requiredTerms);

        String title = text(llm, "title");
        String company = text(llm, "company");
        String location = text(llm, "location");
        if (source != null) {
            title = firstNonBlank(source.title(), title);
            company = firstNonBlank(source.company(), company);
            location = firstNonBlank(location, source.location());
            if (source.salaryMin() != null || source.salaryMax() != null) {
                range = new SalaryRange(source.salaryMin(), source.salaryMax(), source.salaryCurrency());
            }
        }
        return new JobSpec(title, company, location, text(llm, "seniority"), text(llm, "employmentType"), range,
                required, preferred, strings(llm.path("responsibilities")), strings(llm.path("qualifications")),
                strings(llm.path("keywordsRaw")), strings(llm.path("companyFacts")), text(llm, "applyInstructions"),
                llm.path("coverLetterRequested").asBoolean(false), strings(llm.path("warnings")));
    }

    private static List<JdSkill> skills(JsonNode array, Set<String> exclude) {
        List<JdSkill> skills = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(exclude);
        for (JsonNode s : array.values()) {
            String term = s.path("term").stringValue("").strip();
            if (!term.isEmpty() && seen.add(term.toLowerCase(Locale.ROOT))) {
                List<String> aliases = strings(s.path("aliases")).stream()
                        .filter(a -> !a.equalsIgnoreCase(term)).distinct().toList();
                skills.add(new JdSkill(term, aliases));
            }
        }
        return skills;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.values().forEach(v -> {
            String s = v.stringValue("").strip();
            if (!s.isEmpty() && !values.contains(s)) {
                values.add(s);
            }
        });
        return values;
    }

    private static String text(JsonNode node, String key) {
        return blankToNull(node.path(key).stringValue("").strip());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : blankToNull(b);
    }
}
