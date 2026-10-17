package com.bu.tailorsg.render;

/**
 * Spec 4 date style: "MMM YYYY - MMM YYYY" or "MMM YYYY - Present", ASCII hyphen with spaces.
 *
 * <p>Month names come from a fixed table rather than a locale: Java's UK locale now abbreviates
 * September as "Sept", which would break the single "MMM YYYY" pattern SG-10 checks for.
 */
public final class ResumeDates {

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private ResumeDates() {}

    /** "2024-05" becomes "May 2024"; anything else is returned unchanged. */
    public static String monthYear(String yearMonth) {
        if (yearMonth == null || !yearMonth.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            return yearMonth == null ? "" : yearMonth;
        }
        return MONTHS[Integer.parseInt(yearMonth.substring(5)) - 1] + " " + yearMonth.substring(0, 4);
    }

    /** "May 2024 - Present" when end is null and start is known; "" when both are missing. */
    public static String range(String start, String end) {
        if (start == null && end == null) {
            return "";
        }
        if (start == null) {
            return monthYear(end);
        }
        return monthYear(start) + " - " + (end == null ? "Present" : monthYear(end));
    }
}
