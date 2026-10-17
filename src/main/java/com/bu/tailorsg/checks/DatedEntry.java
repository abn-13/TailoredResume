package com.bu.tailorsg.checks;

/**
 * An entry with dates, in document order, for the reverse-chronological rule (SG-10).
 *
 * @param section   e.g. "Experience", "Education"
 * @param startDate YYYY-MM or null
 * @param endDate   YYYY-MM, or null for current
 */
public record DatedEntry(String section, String id, String label, String startDate, String endDate) {}
