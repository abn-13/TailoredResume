package com.bu.tailorsg.applications;

/**
 * What the user chose for one application (spec 3: pageTarget, includeSummary, showWorkAuth, coverLetter,
 * tone, and the cover letter's USER_NOTES).
 *
 * @param whyCompany      USER_NOTES: the user's own reasons for this company (the cover letter may only use these)
 * @param availability    USER_NOTES: e.g. "available from January 2027"
 * @param mentionWorkPass USER_NOTES: allow one neutral work-authorisation sentence in the cover letter
 */
public record ApplicationOptions(int pageTarget, boolean includeSummary, boolean showWorkAuth, boolean coverLetter,
                                 String tone, String whyCompany, String availability, boolean mentionWorkPass) {

    public ApplicationOptions {
        pageTarget = pageTarget == 2 ? 2 : 1;
        tone = tone == null || tone.isBlank() ? "warm and direct" : tone.strip();
    }

    public static ApplicationOptions defaults() {
        return new ApplicationOptions(1, true, true, true, null, null, null, false);
    }
}
