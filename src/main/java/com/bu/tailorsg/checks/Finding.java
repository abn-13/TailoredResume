package com.bu.tailorsg.checks;

/**
 * One result of one rule (spec 6: checkId, severity, location, message, suggestedFix, evidence).
 *
 * @param location     where: a bullet anchor such as "b-work-1.h2", a section, or "page"; may be empty
 * @param suggestedFix what to do about it; may be empty
 * @param evidence     the offending text; may be empty
 */
public record Finding(String checkId, Severity severity, String location, String message, String suggestedFix,
                      String evidence) {

    public static Finding of(String checkId, Severity severity, String location, String message) {
        return new Finding(checkId, severity, location, message, "", "");
    }

    public Finding withFix(String fix) {
        return new Finding(checkId, severity, location, message, fix, evidence);
    }

    public Finding withEvidence(String text) {
        return new Finding(checkId, severity, location, message, suggestedFix, text);
    }
}
