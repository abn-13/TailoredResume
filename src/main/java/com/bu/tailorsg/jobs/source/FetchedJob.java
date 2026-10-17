package com.bu.tailorsg.jobs.source;

import java.util.List;

/**
 * A job posting as a source returned it: plain text for the extractor model, plus any structured fields the
 * site provides. Structured fields are trusted over the model's reading of the text.
 *
 * @param source    which adapter produced it, e.g. "MyCareersFuture"
 * @param skills    skills the site lists (MyCareersFuture), may be empty
 * @param salaryMin monthly salary bounds when the site states them, else null
 */
public record FetchedJob(String source, String title, String company, String location, String text,
                         List<String> skills, Double salaryMin, Double salaryMax, String salaryCurrency) {

    public FetchedJob {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    public static FetchedJob textOnly(String source, String title, String company, String text) {
        return new FetchedJob(source, title, company, null, text, List.of(), null, null, null);
    }
}
