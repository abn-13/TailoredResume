package com.bu.tailorsg.checks.ats;

import java.util.List;

/**
 * Reads resume fields back out of extracted text, the way an applicant tracking system would (ATS-10).
 * An interface so a better parser can replace the heuristic one without touching the rule.
 */
public interface ResumeParser {

    ParsedResume parse(String extractedText);

    /** What a parser recovered. Items are in document order. */
    record ParsedResume(String name, String email, String phone, List<Item> experience, List<Item> education,
                        List<String> headings) {}

    /**
     * One job or degree.
     *
     * @param title   position, or degree
     * @param org     employer, or institution
     * @param dates   e.g. "May 2024 - Present", or ""
     */
    record Item(String title, String org, String dates) {}
}
