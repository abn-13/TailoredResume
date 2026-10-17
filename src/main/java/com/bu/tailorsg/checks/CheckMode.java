package com.bu.tailorsg.checks;

/** What is being checked; each rule declares the modes it applies to. */
public enum CheckMode {
    /** The whole master resume (Master health page). */
    MASTER,
    /** A tailored resume, as rendered (before export). */
    RESUME,
    /** An exported PDF or DOCX: RESUME rules plus file-based ones (ATS, images, real page count). */
    EXPORT,
    /** A cover letter. */
    COVER_LETTER
}
